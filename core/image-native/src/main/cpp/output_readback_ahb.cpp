#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/sync.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <unistd.h>

namespace {
constexpr std::uint64_t kInputFloatsPerPixel = 4;
constexpr std::uint64_t kOutputBytesPerPixel = 4;

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    env->ThrowNew(type, message);
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

bool validate_blob(AHardwareBuffer* buffer, std::uint64_t requiredBytes) {
    if (buffer == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    return desc.format == AHARDWAREBUFFER_FORMAT_BLOB &&
        desc.height == 1 &&
        desc.layers == 1 &&
        desc.width >= requiredBytes &&
        (desc.usage & AHARDWAREBUFFER_USAGE_CPU_READ_RARELY) != 0;
}

std::uint8_t quantize(float value) {
    const float clamped = std::clamp(value, 0.0f, 1.0f);
    return static_cast<std::uint8_t>(std::lround(clamped * 255.0f));
}
}

extern "C" JNIEXPORT void JNICALL
Java_app_lifeos_core_image_nativebackend_NativeMmsiOutputReadbackBridge_nativeReadRgba8(
    JNIEnv* env,
    jobject,
    jobject outputHardwareBuffer,
    jobject rgba8Buffer,
    jint pixelCount,
    jint acquireFenceFd,
    jint timeoutMs
) {
    auto closeFence = [&]() {
        if (acquireFenceFd >= 0) {
            close(acquireFenceFd);
            acquireFenceFd = -1;
        }
    };

    if (pixelCount <= 0 || timeoutMs <= 0) {
        closeFence();
        throw_illegal_argument(env, "pixelCount and timeoutMs must be positive");
        return;
    }

    const std::uint64_t pixels = static_cast<std::uint64_t>(pixelCount);
    const std::uint64_t inputBytes = pixels * kInputFloatsPerPixel * sizeof(float);
    const std::uint64_t outputBytes = pixels * kOutputBytesPerPixel;
    AHardwareBuffer* output = outputHardwareBuffer == nullptr
        ? nullptr
        : AHardwareBuffer_fromHardwareBuffer(env, outputHardwareBuffer);
    if (!validate_blob(output, inputBytes)) {
        closeFence();
        throw_illegal_argument(env, "Output HardwareBuffer does not satisfy the RGBA32F readback contract");
        return;
    }

    void* destination = rgba8Buffer == nullptr ? nullptr : env->GetDirectBufferAddress(rgba8Buffer);
    if (destination == nullptr || env->GetDirectBufferCapacity(rgba8Buffer) < static_cast<jlong>(outputBytes)) {
        closeFence();
        throw_illegal_argument(env, "RGBA8 destination must be a sufficiently large direct ByteBuffer");
        return;
    }

    if (acquireFenceFd >= 0) {
        const int waitResult = sync_wait(acquireFenceFd, timeoutMs);
        closeFence();
        if (waitResult < 0) {
            throw_illegal_state(env, "Timed out waiting for MMSI render completion");
            return;
        }
    }

    void* mapped = nullptr;
    if (AHardwareBuffer_lock(
            output,
            AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
            -1,
            nullptr,
            &mapped) != 0 || mapped == nullptr) {
        throw_illegal_state(env, "Could not lock MMSI output HardwareBuffer for readback");
        return;
    }

    const auto* source = static_cast<const float*>(mapped);
    auto* target = static_cast<std::uint8_t*>(destination);
    bool finite = true;
    for (std::size_t i = 0; i < static_cast<std::size_t>(pixels); ++i) {
        const std::size_t base = i * 4;
        for (std::size_t channel = 0; channel < 4; ++channel) {
            const float value = source[base + channel];
            if (!std::isfinite(value)) {
                finite = false;
                target[base + channel] = 0;
            } else {
                target[base + channel] = quantize(value);
            }
        }
    }

    int32_t releaseFence = -1;
    const int unlockResult = AHardwareBuffer_unlock(output, &releaseFence);
    if (releaseFence >= 0) close(releaseFence);
    if (unlockResult != 0) {
        throw_illegal_state(env, "Could not unlock MMSI output HardwareBuffer");
        return;
    }
    if (!finite) {
        throw_illegal_state(env, "MMSI output contains non-finite pixel values");
    }
}
