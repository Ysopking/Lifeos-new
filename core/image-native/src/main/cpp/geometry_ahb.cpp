#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/sync.h>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <unistd.h>

namespace {
constexpr int kNormalFloatsPerPixel = 3;
constexpr int kNormalDepthFloatsPerPixel = 4;
constexpr int kRoughnessFloatsPerPixel = 1;

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    env->ThrowNew(type, message);
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

bool require_direct(JNIEnv* env, jobject buffer, std::uint64_t requiredBytes) {
    return buffer != nullptr &&
        env->GetDirectBufferAddress(buffer) != nullptr &&
        env->GetDirectBufferCapacity(buffer) >= static_cast<jlong>(requiredBytes);
}

bool validate_blob(AHardwareBuffer* buffer, std::uint64_t requiredBytes, std::uint64_t requiredUsage) {
    if (buffer == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    return desc.format == AHARDWAREBUFFER_FORMAT_BLOB &&
        desc.height == 1 &&
        desc.layers == 1 &&
        desc.width >= requiredBytes &&
        (desc.usage & requiredUsage) == requiredUsage;
}

int merge_owned_fences(const char* name, int first, int second) {
    if (first < 0) return second;
    if (second < 0) return first;
    const int merged = sync_merge(name, first, second);
    close(first);
    close(second);
    return merged;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lifeos_core_image_nativebackend_NativeGeometryHardwareBufferBridge_nativeUploadGeometry(
    JNIEnv* env,
    jobject,
    jobject normalBuffer,
    jobject depthBuffer,
    jobject roughnessBuffer,
    jobject normalDepthHardwareBuffer,
    jobject roughnessHardwareBuffer,
    jint pixelCount
) {
    if (pixelCount < 0) {
        throw_illegal_argument(env, "pixelCount must be non-negative");
        return -1;
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(pixelCount);
    const std::uint64_t normalBytes = pixels * kNormalFloatsPerPixel * sizeof(float);
    const std::uint64_t depthBytes = pixels * sizeof(float);
    const std::uint64_t roughnessBytes = pixels * sizeof(float);
    if (!require_direct(env, normalBuffer, normalBytes) ||
        !require_direct(env, depthBuffer, depthBytes) ||
        !require_direct(env, roughnessBuffer, roughnessBytes)) {
        throw_illegal_argument(env, "Geometry inputs must be direct buffers with sufficient capacity");
        return -1;
    }

    AHardwareBuffer* normalDepth = normalDepthHardwareBuffer == nullptr
        ? nullptr
        : AHardwareBuffer_fromHardwareBuffer(env, normalDepthHardwareBuffer);
    AHardwareBuffer* roughness = roughnessHardwareBuffer == nullptr
        ? nullptr
        : AHardwareBuffer_fromHardwareBuffer(env, roughnessHardwareBuffer);
    if (!validate_blob(
            normalDepth,
            pixels * kNormalDepthFloatsPerPixel * sizeof(float),
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY) ||
        !validate_blob(
            roughness,
            pixels * kRoughnessFloatsPerPixel * sizeof(float),
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY)) {
        throw_illegal_argument(env, "Geometry HardwareBuffers do not satisfy the storage contract");
        return -1;
    }

    void* normalDepthMapped = nullptr;
    if (AHardwareBuffer_lock(
            normalDepth,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1,
            nullptr,
            &normalDepthMapped) != 0 || normalDepthMapped == nullptr) {
        throw_illegal_state(env, "Could not lock normal/depth HardwareBuffer");
        return -1;
    }

    void* roughnessMapped = nullptr;
    if (AHardwareBuffer_lock(
            roughness,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1,
            nullptr,
            &roughnessMapped) != 0 || roughnessMapped == nullptr) {
        AHardwareBuffer_unlock(normalDepth, nullptr);
        throw_illegal_state(env, "Could not lock roughness HardwareBuffer");
        return -1;
    }

    const auto* normals = static_cast<const float*>(env->GetDirectBufferAddress(normalBuffer));
    const auto* depths = static_cast<const float*>(env->GetDirectBufferAddress(depthBuffer));
    const auto* roughnessIn = static_cast<const float*>(env->GetDirectBufferAddress(roughnessBuffer));
    auto* normalDepthOut = static_cast<float*>(normalDepthMapped);
    auto* roughnessOut = static_cast<float*>(roughnessMapped);

    for (std::size_t i = 0; i < static_cast<std::size_t>(pixelCount); ++i) {
        const std::size_t normalBase = i * kNormalFloatsPerPixel;
        const std::size_t outBase = i * kNormalDepthFloatsPerPixel;
        normalDepthOut[outBase + 0] = normals[normalBase + 0];
        normalDepthOut[outBase + 1] = normals[normalBase + 1];
        normalDepthOut[outBase + 2] = normals[normalBase + 2];
        normalDepthOut[outBase + 3] = depths[i];
        roughnessOut[i] = roughnessIn[i];
    }

    int32_t normalDepthFence = -1;
    int32_t roughnessFence = -1;
    const int normalUnlock = AHardwareBuffer_unlock(normalDepth, &normalDepthFence);
    const int roughnessUnlock = AHardwareBuffer_unlock(roughness, &roughnessFence);
    if (normalUnlock != 0 || roughnessUnlock != 0) {
        if (normalDepthFence >= 0) close(normalDepthFence);
        if (roughnessFence >= 0) close(roughnessFence);
        throw_illegal_state(env, "Could not unlock geometry HardwareBuffers");
        return -1;
    }

    const int merged = merge_owned_fences("lifeos-mmsi-geometry", normalDepthFence, roughnessFence);
    if (normalDepthFence >= 0 && roughnessFence >= 0 && merged < 0) {
        throw_illegal_state(env, "Could not merge geometry release fences");
        return -1;
    }
    return static_cast<jint>(merged);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lifeos_core_image_nativebackend_NativeMmsiSyncBridge_nativeMerge(
    JNIEnv*,
    jobject,
    jint firstFenceFd,
    jint secondFenceFd
) {
    return static_cast<jint>(merge_owned_fences(
        "lifeos-mmsi-pipeline",
        static_cast<int>(firstFenceFd),
        static_cast<int>(secondFenceFd)
    ));
}
