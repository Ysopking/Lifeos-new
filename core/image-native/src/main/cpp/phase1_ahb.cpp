#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <unistd.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kMinNdot = 0.001f;
constexpr float kMinRoughness = 0.04f;
constexpr std::size_t kOutputFloatsPerPixel = 4;

struct Context {
    float lx;
    float ly;
    float lz;
    float sunIntensity;
    float ambientIntensity;
};

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    env->ThrowNew(type, message);
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

bool require_direct(JNIEnv* env, jobject buffer, jlong bytes, const char* message) {
    if (buffer == nullptr || env->GetDirectBufferAddress(buffer) == nullptr || env->GetDirectBufferCapacity(buffer) < bytes) {
        throw_illegal_argument(env, message);
        return false;
    }
    return true;
}

inline float srgb_to_linear(float value) {
    const float v = std::clamp(value, 0.0f, 1.0f);
    return v <= 0.04045f
        ? v / 12.92f
        : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

inline void stable_half_vector(const Context& ctx, float& hx, float& hy, float& hz) {
    hx = ctx.lx;
    hy = ctx.ly;
    hz = ctx.lz + 1.0f;
    const float length2 = hx * hx + hy * hy + hz * hz;
    if (length2 <= 1e-12f) {
        hx = 0.0f;
        hy = 0.0f;
        hz = 1.0f;
        return;
    }
    const float inv = 1.0f / std::sqrt(length2);
    hx *= inv;
    hy *= inv;
    hz *= inv;
}

inline void process_scalar_pixel_rgba(
    const std::uint8_t* rgb,
    const float* normals,
    const float* roughness,
    float* output,
    std::size_t i,
    const Context& ctx,
    float hx,
    float hy,
    float hz
) {
    const std::size_t inputBase = i * 3;
    const std::size_t outputBase = i * kOutputFloatsPerPixel;
    const float nx = normals[inputBase + 0];
    const float ny = normals[inputBase + 1];
    const float nz = normals[inputBase + 2];
    const float ndotl = std::max(kMinNdot, nx * ctx.lx + ny * ctx.ly + nz * ctx.lz);
    const float ndoth = std::max(0.0f, nx * hx + ny * hy + nz * hz);
    const float ndotv = std::max(kMinNdot, nz);
    const float rough = std::max(kMinRoughness, roughness[i]);
    const float alpha = rough * rough;
    const float alphaSq = alpha * alpha;
    const float denom = ndoth * ndoth * (alphaSq - 1.0f) + 1.0f;
    const float dGgx = alphaSq / (kPi * denom * denom);
    const float specular = (dGgx * 0.04f) / (4.0f * ndotl * ndotv);
    const float totalLight = std::max(1e-6f, ctx.sunIntensity * ndotl + ctx.ambientIntensity);

    const float r = srgb_to_linear(rgb[inputBase + 0] / 255.0f);
    const float g = srgb_to_linear(rgb[inputBase + 1] / 255.0f);
    const float b = srgb_to_linear(rgb[inputBase + 2] / 255.0f);
    output[outputBase + 0] = std::clamp((r - specular) / totalLight, 0.0f, 1.0f);
    output[outputBase + 1] = std::clamp((g - specular) / totalLight, 0.0f, 1.0f);
    output[outputBase + 2] = std::clamp((b - specular) / totalLight, 0.0f, 1.0f);
    output[outputBase + 3] = 1.0f;
}

#if defined(__aarch64__)
void inverse_radiometry_rgba_neon(
    const std::uint8_t* rgb,
    const float* normals,
    const float* roughness,
    float* output,
    std::size_t count,
    const Context& ctx
) {
    float hx, hy, hz;
    stable_half_vector(ctx, hx, hy, hz);
    const float32x4_t lx = vdupq_n_f32(ctx.lx);
    const float32x4_t ly = vdupq_n_f32(ctx.ly);
    const float32x4_t lz = vdupq_n_f32(ctx.lz);
    const float32x4_t hvx = vdupq_n_f32(hx);
    const float32x4_t hvy = vdupq_n_f32(hy);
    const float32x4_t hvz = vdupq_n_f32(hz);
    const float32x4_t minNdot = vdupq_n_f32(kMinNdot);
    const float32x4_t zero = vdupq_n_f32(0.0f);
    const float32x4_t one = vdupq_n_f32(1.0f);
    const float32x4_t minRough = vdupq_n_f32(kMinRoughness);
    const float32x4_t pi = vdupq_n_f32(kPi);
    const float32x4_t f004 = vdupq_n_f32(0.04f);
    const float32x4_t four = vdupq_n_f32(4.0f);
    const float32x4_t sunIntensity = vdupq_n_f32(ctx.sunIntensity);
    const float32x4_t ambient = vdupq_n_f32(ctx.ambientIntensity);
    const float32x4_t minLight = vdupq_n_f32(1e-6f);

    alignas(16) float rLinear[4];
    alignas(16) float gLinear[4];
    alignas(16) float bLinear[4];
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) {
        const std::size_t inputBase = i * 3;
        const float32x4x3_t n = vld3q_f32(normals + inputBase);
        float32x4_t ndotl = vmulq_f32(n.val[0], lx);
        ndotl = vmlaq_f32(ndotl, n.val[1], ly);
        ndotl = vmlaq_f32(ndotl, n.val[2], lz);
        ndotl = vmaxq_f32(ndotl, minNdot);

        float32x4_t ndoth = vmulq_f32(n.val[0], hvx);
        ndoth = vmlaq_f32(ndoth, n.val[1], hvy);
        ndoth = vmlaq_f32(ndoth, n.val[2], hvz);
        ndoth = vmaxq_f32(ndoth, zero);
        const float32x4_t ndotv = vmaxq_f32(n.val[2], minNdot);
        const float32x4_t rough = vmaxq_f32(vld1q_f32(roughness + i), minRough);
        const float32x4_t alpha = vmulq_f32(rough, rough);
        const float32x4_t alphaSq = vmulq_f32(alpha, alpha);
        const float32x4_t ndothSq = vmulq_f32(ndoth, ndoth);
        const float32x4_t denom = vaddq_f32(vmulq_f32(ndothSq, vsubq_f32(alphaSq, one)), one);
        const float32x4_t dGgx = vdivq_f32(alphaSq, vmulq_f32(pi, vmulq_f32(denom, denom)));
        const float32x4_t specular = vdivq_f32(
            vmulq_f32(dGgx, f004),
            vmulq_f32(four, vmulq_f32(ndotl, ndotv))
        );
        const float32x4_t totalLight = vmaxq_f32(vaddq_f32(vmulq_f32(sunIntensity, ndotl), ambient), minLight);

        for (int lane = 0; lane < 4; ++lane) {
            const std::size_t p = (i + static_cast<std::size_t>(lane)) * 3;
            rLinear[lane] = srgb_to_linear(rgb[p + 0] / 255.0f);
            gLinear[lane] = srgb_to_linear(rgb[p + 1] / 255.0f);
            bLinear[lane] = srgb_to_linear(rgb[p + 2] / 255.0f);
        }

        float32x4x4_t out{};
        out.val[0] = vminq_f32(one, vmaxq_f32(zero, vdivq_f32(vsubq_f32(vld1q_f32(rLinear), specular), totalLight)));
        out.val[1] = vminq_f32(one, vmaxq_f32(zero, vdivq_f32(vsubq_f32(vld1q_f32(gLinear), specular), totalLight)));
        out.val[2] = vminq_f32(one, vmaxq_f32(zero, vdivq_f32(vsubq_f32(vld1q_f32(bLinear), specular), totalLight)));
        out.val[3] = one;
        vst4q_f32(output + i * kOutputFloatsPerPixel, out);
    }
    for (; i < count; ++i) {
        process_scalar_pixel_rgba(rgb, normals, roughness, output, i, ctx, hx, hy, hz);
    }
}
#endif

void inverse_radiometry_rgba(
    const std::uint8_t* rgb,
    const float* normals,
    const float* roughness,
    float* output,
    std::size_t count,
    const Context& ctx
) {
#if defined(__aarch64__)
    inverse_radiometry_rgba_neon(rgb, normals, roughness, output, count, ctx);
#else
    float hx, hy, hz;
    stable_half_vector(ctx, hx, hy, hz);
    for (std::size_t i = 0; i < count; ++i) {
        process_scalar_pixel_rgba(rgb, normals, roughness, output, i, ctx, hx, hy, hz);
    }
#endif
}
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lifeos_core_image_nativebackend_NativeHardwareBufferDeLightingBridge_nativeInverseRadiometryToHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject rgbBuffer,
    jobject normalBuffer,
    jobject roughnessBuffer,
    jobject outputHardwareBuffer,
    jint pixelCount,
    jfloat sunX,
    jfloat sunY,
    jfloat sunZ,
    jfloat sunIntensity,
    jfloat ambientIntensity,
    jint acquireFenceFd
) {
    if (pixelCount < 0) {
        throw_illegal_argument(env, "pixelCount must be non-negative");
        return -1;
    }
    const jlong rgbBytes = static_cast<jlong>(pixelCount) * 3;
    const jlong normalBytes = static_cast<jlong>(pixelCount) * 3 * sizeof(float);
    const jlong roughnessBytes = static_cast<jlong>(pixelCount) * sizeof(float);
    if (!require_direct(env, rgbBuffer, rgbBytes, "rgbBuffer must be direct and large enough") ||
        !require_direct(env, normalBuffer, normalBytes, "normalBuffer must be direct and large enough") ||
        !require_direct(env, roughnessBuffer, roughnessBytes, "roughnessBuffer must be direct and large enough")) {
        return -1;
    }
    if (outputHardwareBuffer == nullptr) {
        throw_illegal_argument(env, "output HardwareBuffer is required");
        return -1;
    }

    AHardwareBuffer* output = AHardwareBuffer_fromHardwareBuffer(env, outputHardwareBuffer);
    if (output == nullptr) {
        throw_illegal_argument(env, "output HardwareBuffer could not be resolved");
        return -1;
    }
    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(output, &description);
    const std::uint64_t requiredBytes = static_cast<std::uint64_t>(pixelCount) * 4u * sizeof(float);
    if (description.format != AHARDWAREBUFFER_FORMAT_BLOB || description.height != 1 || description.width < requiredBytes) {
        throw_illegal_argument(env, "output HardwareBuffer must be a large enough BLOB RGBA32F storage buffer");
        return -1;
    }
    if ((description.usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY) == 0) {
        throw_illegal_argument(env, "output HardwareBuffer must allow CPU_WRITE_RARELY");
        return -1;
    }

    void* mapped = nullptr;
    const int lockResult = AHardwareBuffer_lock(
        output,
        AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
        acquireFenceFd,
        nullptr,
        &mapped
    );
    if (lockResult != 0 || mapped == nullptr) {
        throw_illegal_state(env, "AHardwareBuffer_lock failed for Phase 1 output");
        return -1;
    }

    const auto* rgb = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(rgbBuffer));
    const auto* normals = static_cast<const float*>(env->GetDirectBufferAddress(normalBuffer));
    const auto* roughness = static_cast<const float*>(env->GetDirectBufferAddress(roughnessBuffer));
    auto* albedo = static_cast<float*>(mapped);
    const Context context{sunX, sunY, sunZ, sunIntensity, ambientIntensity};
    inverse_radiometry_rgba(rgb, normals, roughness, albedo, static_cast<std::size_t>(pixelCount), context);

    int32_t releaseFenceFd = -1;
    const int unlockResult = AHardwareBuffer_unlock(output, &releaseFenceFd);
    if (unlockResult != 0) {
        if (releaseFenceFd >= 0) close(releaseFenceFd);
        throw_illegal_state(env, "AHardwareBuffer_unlock failed for Phase 1 output");
        return -1;
    }
    return static_cast<jint>(releaseFenceFd);
}
