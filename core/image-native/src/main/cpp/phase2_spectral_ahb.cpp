#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <cstddef>
#include <cstdint>
#include <unistd.h>

namespace {
constexpr int kComponents = 6;
constexpr int kRgbChannels = 3;
constexpr int kProjectionFloats = kComponents + kComponents * kRgbChannels;
constexpr int kAlbedoFloatsPerPixel = 4;
constexpr int kPackedCoefficientFloatsPerPixel = 8;

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    env->ThrowNew(type, message);
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

bool validate_blob(
    AHardwareBuffer* buffer,
    std::uint64_t requiredBytes,
    std::uint64_t requiredUsage
) {
    if (buffer == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    return desc.format == AHARDWAREBUFFER_FORMAT_BLOB &&
        desc.height == 1 &&
        desc.layers == 1 &&
        desc.width >= requiredBytes &&
        (desc.usage & requiredUsage) == requiredUsage;
}

void expand_coefficients(
    const float* albedo,
    float* packed,
    std::size_t pixelCount,
    const float* projection
) {
    const float* bias = projection;
    const float* matrix = projection + kComponents;
    for (std::size_t pixel = 0; pixel < pixelCount; ++pixel) {
        const std::size_t inBase = pixel * kAlbedoFloatsPerPixel;
        const std::size_t outBase = pixel * kPackedCoefficientFloatsPerPixel;
        const float r = albedo[inBase + 0];
        const float g = albedo[inBase + 1];
        const float b = albedo[inBase + 2];
        for (int component = 0; component < kComponents; ++component) {
            const int row = component * kRgbChannels;
            packed[outBase + component] = bias[component] +
                matrix[row + 0] * r +
                matrix[row + 1] * g +
                matrix[row + 2] * b;
        }
        packed[outBase + 6] = 0.0f;
        packed[outBase + 7] = 0.0f;
    }
}
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lifeos_core_image_nativebackend_NativeSpectralCoefficientBridge_nativeExpandToHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject albedoHardwareBuffer,
    jobject coefficientHardwareBuffer,
    jint pixelCount,
    jobject projectionBuffer,
    jint acquireFenceFd
) {
    if (pixelCount < 0) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        throw_illegal_argument(env, "pixelCount must be non-negative");
        return -1;
    }
    if (projectionBuffer == nullptr || env->GetDirectBufferAddress(projectionBuffer) == nullptr ||
        env->GetDirectBufferCapacity(projectionBuffer) < static_cast<jlong>(kProjectionFloats * sizeof(float))) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        throw_illegal_argument(env, "projection buffer must contain 24 float values");
        return -1;
    }

    AHardwareBuffer* albedo = albedoHardwareBuffer == nullptr
        ? nullptr
        : AHardwareBuffer_fromHardwareBuffer(env, albedoHardwareBuffer);
    AHardwareBuffer* coefficients = coefficientHardwareBuffer == nullptr
        ? nullptr
        : AHardwareBuffer_fromHardwareBuffer(env, coefficientHardwareBuffer);
    const std::uint64_t pixels = static_cast<std::uint64_t>(pixelCount);
    if (!validate_blob(
            albedo,
            pixels * kAlbedoFloatsPerPixel * sizeof(float),
            AHARDWAREBUFFER_USAGE_CPU_READ_RARELY) ||
        !validate_blob(
            coefficients,
            pixels * kPackedCoefficientFloatsPerPixel * sizeof(float),
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY)) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        throw_illegal_argument(env, "Phase 2 HardwareBuffers do not satisfy the spectral storage contract");
        return -1;
    }

    void* albedoMapped = nullptr;
    const int inputLock = AHardwareBuffer_lock(
        albedo,
        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
        acquireFenceFd,
        nullptr,
        &albedoMapped
    );
    if (inputLock != 0 || albedoMapped == nullptr) {
        throw_illegal_state(env, "Could not lock Phase 1 albedo for spectral expansion");
        return -1;
    }

    void* coefficientMapped = nullptr;
    const int outputLock = AHardwareBuffer_lock(
        coefficients,
        AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
        -1,
        nullptr,
        &coefficientMapped
    );
    if (outputLock != 0 || coefficientMapped == nullptr) {
        AHardwareBuffer_unlock(albedo, nullptr);
        throw_illegal_state(env, "Could not lock spectral coefficient HardwareBuffer");
        return -1;
    }

    const auto* projection = static_cast<const float*>(env->GetDirectBufferAddress(projectionBuffer));
    expand_coefficients(
        static_cast<const float*>(albedoMapped),
        static_cast<float*>(coefficientMapped),
        static_cast<std::size_t>(pixelCount),
        projection
    );

    int32_t releaseFenceFd = -1;
    const int outputUnlock = AHardwareBuffer_unlock(coefficients, &releaseFenceFd);
    const int inputUnlock = AHardwareBuffer_unlock(albedo, nullptr);
    if (outputUnlock != 0 || inputUnlock != 0) {
        if (releaseFenceFd >= 0) close(releaseFenceFd);
        throw_illegal_state(env, "Could not unlock Phase 2 HardwareBuffers");
        return -1;
    }
    return static_cast<jint>(releaseFenceFd);
}
