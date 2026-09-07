#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <cstdint>
#include <cstring>
#include <unistd.h>

namespace {
constexpr int kAlbedoFloatsPerPixel = 4;

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

bool validate_blob(AHardwareBuffer* buffer, std::uint64_t requiredBytes) {
    if (buffer == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    return desc.format == AHARDWAREBUFFER_FORMAT_BLOB &&
        desc.height == 1 &&
        desc.layers == 1 &&
        desc.width >= requiredBytes &&
        (desc.usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY) != 0;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lifeos_core_image_nativebackend_NativeIntrinsicAlbedoHardwareBufferBridge_nativeUploadIntrinsicAlbedo(
    JNIEnv* env,
    jobject,
    jobject albedoBuffer,
    jobject albedoHardwareBuffer,
    jint pixelCount
) {
    if (pixelCount < 0) {
        throw_illegal_argument(env, "pixelCount must be non-negative");
        return -1;
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(pixelCount);
    const std::uint64_t bytes = pixels * kAlbedoFloatsPerPixel * sizeof(float);
    if (!require_direct(env, albedoBuffer, bytes)) {
        throw_illegal_argument(env, "Intrinsic albedo input must be a direct RGBA32F buffer");
        return -1;
    }

    AHardwareBuffer* output = albedoHardwareBuffer == nullptr
        ? nullptr
        : AHardwareBuffer_fromHardwareBuffer(env, albedoHardwareBuffer);
    if (!validate_blob(output, bytes)) {
        throw_illegal_argument(env, "Albedo HardwareBuffer does not satisfy the RGBA32F storage contract");
        return -1;
    }

    void* mapped = nullptr;
    if (AHardwareBuffer_lock(
            output,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1,
            nullptr,
            &mapped) != 0 || mapped == nullptr) {
        throw_illegal_state(env, "Could not lock intrinsic albedo HardwareBuffer");
        return -1;
    }

    const void* source = env->GetDirectBufferAddress(albedoBuffer);
    std::memcpy(mapped, source, static_cast<std::size_t>(bytes));

    int32_t releaseFence = -1;
    const int unlockResult = AHardwareBuffer_unlock(output, &releaseFence);
    if (unlockResult != 0) {
        if (releaseFence >= 0) close(releaseFence);
        throw_illegal_state(env, "Could not unlock intrinsic albedo HardwareBuffer");
        return -1;
    }
    return static_cast<jint>(releaseFence);
}
