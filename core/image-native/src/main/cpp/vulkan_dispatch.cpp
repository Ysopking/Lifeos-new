#include <jni.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {
struct BufferAllocation {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};

struct Renderer {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    std::uint32_t queueFamilyIndex = 0;
    VkQueue queue = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
};

struct alignas(16) SynthesisParamsNative {
    float sunDirectionAndSolidAngle[4];
    float sunColor[4];
    float skyAmbientColor[4];
    std::uint32_t resolution[2];
    float shadowFloor;
    float padding;
};
static_assert(sizeof(SynthesisParamsNative) == 64);

bool find_compute_device(VkInstance instance, VkPhysicalDevice& deviceOut, std::uint32_t& queueFamilyOut) {
    std::uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr) != VK_SUCCESS || deviceCount == 0) return false;
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data()) != VK_SUCCESS) return false;
    for (VkPhysicalDevice device : devices) {
        std::uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
        for (std::uint32_t i = 0; i < queueCount; ++i) {
            if (queues[i].queueCount > 0 && (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                deviceOut = device;
                queueFamilyOut = i;
                return true;
            }
        }
    }
    return false;
}

std::uint32_t find_memory_type(VkPhysicalDevice physicalDevice, std::uint32_t typeBits, VkMemoryPropertyFlags required) {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &properties);
    for (std::uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) != 0 && (properties.memoryTypes[i].propertyFlags & required) == required) return i;
    }
    return UINT32_MAX;
}

bool create_host_buffer(Renderer* renderer, VkDeviceSize size, VkBufferUsageFlags usage, BufferAllocation& out) {
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(renderer->device, &bufferInfo, nullptr, &out.buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(renderer->device, out.buffer, &requirements);
    const std::uint32_t memoryType = find_memory_type(
        renderer->physicalDevice,
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (memoryType == UINT32_MAX) {
        vkDestroyBuffer(renderer->device, out.buffer, nullptr);
        out.buffer = VK_NULL_HANDLE;
        return false;
    }

    VkMemoryAllocateInfo allocationInfo{};
    allocationInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocationInfo.allocationSize = requirements.size;
    allocationInfo.memoryTypeIndex = memoryType;
    if (vkAllocateMemory(renderer->device, &allocationInfo, nullptr, &out.memory) != VK_SUCCESS) {
        vkDestroyBuffer(renderer->device, out.buffer, nullptr);
        out.buffer = VK_NULL_HANDLE;
        return false;
    }
    if (vkBindBufferMemory(renderer->device, out.buffer, out.memory, 0) != VK_SUCCESS) {
        vkFreeMemory(renderer->device, out.memory, nullptr);
        vkDestroyBuffer(renderer->device, out.buffer, nullptr);
        out = {};
        return false;
    }
    out.size = size;
    return true;
}

void destroy_buffer(Renderer* renderer, BufferAllocation& allocation) {
    if (allocation.buffer != VK_NULL_HANDLE) vkDestroyBuffer(renderer->device, allocation.buffer, nullptr);
    if (allocation.memory != VK_NULL_HANDLE) vkFreeMemory(renderer->device, allocation.memory, nullptr);
    allocation = {};
}

bool upload(Renderer* renderer, const BufferAllocation& allocation, const void* source, std::size_t bytes) {
    if (bytes > allocation.size) return false;
    void* mapped = nullptr;
    if (vkMapMemory(renderer->device, allocation.memory, 0, bytes, 0, &mapped) != VK_SUCCESS) return false;
    std::memcpy(mapped, source, bytes);
    vkUnmapMemory(renderer->device, allocation.memory);
    return true;
}

bool download(Renderer* renderer, const BufferAllocation& allocation, void* destination, std::size_t bytes) {
    if (bytes > allocation.size) return false;
    void* mapped = nullptr;
    if (vkMapMemory(renderer->device, allocation.memory, 0, bytes, 0, &mapped) != VK_SUCCESS) return false;
    std::memcpy(destination, mapped, bytes);
    vkUnmapMemory(renderer->device, allocation.memory);
    return true;
}

void destroy_renderer(Renderer* renderer) {
    if (renderer == nullptr) return;
    if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
    if (renderer->commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(renderer->device, renderer->commandPool, nullptr);
    if (renderer->descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(renderer->device, renderer->descriptorPool, nullptr);
    if (renderer->pipeline != VK_NULL_HANDLE) vkDestroyPipeline(renderer->device, renderer->pipeline, nullptr);
    if (renderer->pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(renderer->device, renderer->pipelineLayout, nullptr);
    if (renderer->descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(renderer->device, renderer->descriptorSetLayout, nullptr);
    if (renderer->device != VK_NULL_HANDLE) vkDestroyDevice(renderer->device, nullptr);
    if (renderer->instance != VK_NULL_HANDLE) vkDestroyInstance(renderer->instance, nullptr);
    delete renderer;
}

Renderer* create_renderer(const std::uint32_t* spirv, std::size_t spirvBytes) {
    if (spirv == nullptr || spirvBytes == 0 || spirvBytes % 4 != 0) return nullptr;
    auto* renderer = new Renderer();

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "LIFEOS MMSI Forward";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "LIFEOS MMSI";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    if (vkCreateInstance(&instanceInfo, nullptr, &renderer->instance) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    if (!find_compute_device(renderer->instance, renderer->physicalDevice, renderer->queueFamilyIndex)) {
        destroy_renderer(renderer);
        return nullptr;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = renderer->queueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    if (vkCreateDevice(renderer->physicalDevice, &deviceInfo, nullptr, &renderer->device) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    vkGetDeviceQueue(renderer->device, renderer->queueFamilyIndex, 0, &renderer->queue);

    VkDescriptorSetLayoutBinding bindings[5]{};
    for (std::uint32_t i = 0; i < 4; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 5;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(renderer->device, &layoutInfo, nullptr, &renderer->descriptorSetLayout) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &renderer->descriptorSetLayout;
    if (vkCreatePipelineLayout(renderer->device, &pipelineLayoutInfo, nullptr, &renderer->pipelineLayout) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirvBytes;
    shaderInfo.pCode = spirv;
    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(renderer->device, &shaderInfo, nullptr, &shaderModule) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shaderModule;
    stageInfo.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = renderer->pipelineLayout;
    const VkResult pipelineResult = vkCreateComputePipelines(renderer->device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &renderer->pipeline);
    vkDestroyShaderModule(renderer->device, shaderModule, nullptr);
    if (pipelineResult != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 4;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[1].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(renderer->device, &poolInfo, nullptr, &renderer->descriptorPool) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = renderer->queueFamilyIndex;
    if (vkCreateCommandPool(renderer->device, &commandPoolInfo, nullptr, &renderer->commandPool) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    return renderer;
}

bool dispatch(
    Renderer* renderer,
    const float* albedo,
    const float* normalDepth,
    const float* roughness,
    float* output,
    std::uint32_t width,
    std::uint32_t height,
    const SynthesisParamsNative& params
) {
    if (renderer == nullptr || width == 0 || height == 0) return false;
    const std::size_t pixels = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    const VkDeviceSize albedoBytes = pixels * 4 * sizeof(float);
    const VkDeviceSize normalBytes = pixels * 4 * sizeof(float);
    const VkDeviceSize roughnessBytes = pixels * sizeof(float);
    const VkDeviceSize outputBytes = pixels * 4 * sizeof(float);

    BufferAllocation buffers[5]{};
    bool ok = create_host_buffer(renderer, albedoBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buffers[0]) &&
        create_host_buffer(renderer, normalBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buffers[1]) &&
        create_host_buffer(renderer, roughnessBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buffers[2]) &&
        create_host_buffer(renderer, outputBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buffers[3]) &&
        create_host_buffer(renderer, sizeof(SynthesisParamsNative), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, buffers[4]);
    if (!ok) {
        for (auto& buffer : buffers) destroy_buffer(renderer, buffer);
        return false;
    }
    ok = upload(renderer, buffers[0], albedo, static_cast<std::size_t>(albedoBytes)) &&
        upload(renderer, buffers[1], normalDepth, static_cast<std::size_t>(normalBytes)) &&
        upload(renderer, buffers[2], roughness, static_cast<std::size_t>(roughnessBytes)) &&
        upload(renderer, buffers[4], &params, sizeof(params));
    if (!ok) {
        for (auto& buffer : buffers) destroy_buffer(renderer, buffer);
        return false;
    }

    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = renderer->descriptorPool;
    allocateInfo.descriptorSetCount = 1;
    allocateInfo.pSetLayouts = &renderer->descriptorSetLayout;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(renderer->device, &allocateInfo, &descriptorSet) != VK_SUCCESS) {
        for (auto& buffer : buffers) destroy_buffer(renderer, buffer);
        return false;
    }

    VkDescriptorBufferInfo infos[5]{};
    for (int i = 0; i < 5; ++i) {
        infos[i].buffer = buffers[i].buffer;
        infos[i].offset = 0;
        infos[i].range = buffers[i].size;
    }
    VkWriteDescriptorSet writes[5]{};
    for (std::uint32_t i = 0; i < 5; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(renderer->device, 5, writes, 0, nullptr);

    VkCommandBufferAllocateInfo commandAllocate{};
    commandAllocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandAllocate.commandPool = renderer->commandPool;
    commandAllocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandAllocate.commandBufferCount = 1;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(renderer->device, &commandAllocate, &commandBuffer) != VK_SUCCESS) {
        vkFreeDescriptorSets(renderer->device, renderer->descriptorPool, 1, &descriptorSet);
        for (auto& buffer : buffers) destroy_buffer(renderer, buffer);
        return false;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    ok = vkBeginCommandBuffer(commandBuffer, &beginInfo) == VK_SUCCESS;
    if (ok) {
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, renderer->pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, renderer->pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
        vkCmdDispatch(commandBuffer, (width + 7u) / 8u, (height + 7u) / 8u, 1);
        ok = vkEndCommandBuffer(commandBuffer) == VK_SUCCESS;
    }

    VkFence fence = VK_NULL_HANDLE;
    if (ok) {
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        ok = vkCreateFence(renderer->device, &fenceInfo, nullptr, &fence) == VK_SUCCESS;
    }
    if (ok) {
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        ok = vkQueueSubmit(renderer->queue, 1, &submitInfo, fence) == VK_SUCCESS;
    }
    if (ok) ok = vkWaitForFences(renderer->device, 1, &fence, VK_TRUE, UINT64_MAX) == VK_SUCCESS;
    if (ok) ok = download(renderer, buffers[3], output, static_cast<std::size_t>(outputBytes));

    if (fence != VK_NULL_HANDLE) vkDestroyFence(renderer->device, fence, nullptr);
    vkFreeCommandBuffers(renderer->device, renderer->commandPool, 1, &commandBuffer);
    vkFreeDescriptorSets(renderer->device, renderer->descriptorPool, 1, &descriptorSet);
    for (auto& buffer : buffers) destroy_buffer(renderer, buffer);
    return ok;
}

bool require_direct(JNIEnv* env, jobject buffer, jlong bytes, const char* message) {
    if (buffer == nullptr || env->GetDirectBufferAddress(buffer) == nullptr || env->GetDirectBufferCapacity(buffer) < bytes) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exceptionClass, message);
        return false;
    }
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanMmsiRenderer_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject shaderBuffer,
    jint shaderBytes
) {
    if (!require_direct(env, shaderBuffer, shaderBytes, "SPIR-V shader buffer must be direct and large enough") || shaderBytes <= 0 || shaderBytes % 4 != 0) return 0;
    auto* shader = static_cast<const std::uint32_t*>(env->GetDirectBufferAddress(shaderBuffer));
    Renderer* renderer = create_renderer(shader, static_cast<std::size_t>(shaderBytes));
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanMmsiRenderer_nativeDispatch(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject albedoBuffer,
    jobject normalDepthBuffer,
    jobject roughnessBuffer,
    jobject outputBuffer,
    jint width,
    jint height,
    jfloat sunX,
    jfloat sunY,
    jfloat sunZ,
    jfloat sunSolidAngle,
    jfloat sunR,
    jfloat sunG,
    jfloat sunB,
    jfloat skyR,
    jfloat skyG,
    jfloat skyB,
    jfloat shadowFloor
) {
    if (width <= 0 || height <= 0 || handle == 0) return JNI_FALSE;
    const jlong pixels = static_cast<jlong>(width) * static_cast<jlong>(height);
    if (!require_direct(env, albedoBuffer, pixels * 4 * sizeof(float), "albedo buffer invalid") ||
        !require_direct(env, normalDepthBuffer, pixels * 4 * sizeof(float), "normal/depth buffer invalid") ||
        !require_direct(env, roughnessBuffer, pixels * sizeof(float), "roughness buffer invalid") ||
        !require_direct(env, outputBuffer, pixels * 4 * sizeof(float), "output buffer invalid")) return JNI_FALSE;

    SynthesisParamsNative params{};
    params.sunDirectionAndSolidAngle[0] = sunX;
    params.sunDirectionAndSolidAngle[1] = sunY;
    params.sunDirectionAndSolidAngle[2] = sunZ;
    params.sunDirectionAndSolidAngle[3] = sunSolidAngle;
    params.sunColor[0] = sunR;
    params.sunColor[1] = sunG;
    params.sunColor[2] = sunB;
    params.sunColor[3] = 1.0f;
    params.skyAmbientColor[0] = skyR;
    params.skyAmbientColor[1] = skyG;
    params.skyAmbientColor[2] = skyB;
    params.skyAmbientColor[3] = 1.0f;
    params.resolution[0] = static_cast<std::uint32_t>(width);
    params.resolution[1] = static_cast<std::uint32_t>(height);
    params.shadowFloor = std::clamp(shadowFloor, 0.0f, 1.0f);

    auto* renderer = reinterpret_cast<Renderer*>(handle);
    const bool ok = dispatch(
        renderer,
        static_cast<const float*>(env->GetDirectBufferAddress(albedoBuffer)),
        static_cast<const float*>(env->GetDirectBufferAddress(normalDepthBuffer)),
        static_cast<const float*>(env->GetDirectBufferAddress(roughnessBuffer)),
        static_cast<float*>(env->GetDirectBufferAddress(outputBuffer)),
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height),
        params
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanMmsiRenderer_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    destroy_renderer(reinterpret_cast<Renderer*>(handle));
}
