package org.example;

// glfw for showing something on the screen. Optional
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.create;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRWin32Surface.VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRWin32Surface.vkGetPhysicalDeviceWin32PresentationSupportKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.Configuration.DEBUG;

public class Main {

    private static class HelloTriangleApplication {

        private static final int WIDTH = 800;
        private static final int HEIGHT = 600;

        // 如果是 debug mode 建立 validation layers
        // 因為 vulcan 本身沒有 test 確定裡面的東西是否正確
        private static final boolean ENABLE_VALIDATION_LAYERS = DEBUG.get(true);
        private static final Set<String> VALIDATION_LAYERS;
        static {
            if(ENABLE_VALIDATION_LAYERS) {
                VALIDATION_LAYERS = new HashSet<>();
                VALIDATION_LAYERS.add("VK_LAYER_KHRONOS_validation");
            } else {
                // We are not going to use it, so we don't create it
                VALIDATION_LAYERS = null;
            }
        }

        private static final Set<String> DEVICE_EXTENSIONS = Stream.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
                .collect(toSet());

        private class QueueFamilyIndices {
            public Integer graphicsFamily;// 專門做 graphics 的 indice // 像是 c++ 的 optional 如果 null 則沒有值
            public Integer presentFamily; // 專門顯示的 Family Queue 可以做 graphics 不代表可以顯示
            public boolean isComplete() {
                return graphicsFamily != null && presentFamily != null;
            }
        }

        private class SwapChainSupportDetails {
            private VkSurfaceCapabilitiesKHR.Buffer capabilities;
            private VkSurfaceFormatKHR.Buffer formats;
            private IntBuffer presentModes;

        }

        // 這個 callback 會存進 debugutilsmessenger
        private static int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) { // long 都是存記憶體位址

            // 這下面居然是個 struct
            VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);

            System.err.println("Validation layer: " + callbackData.pMessageString());

            return VK_FALSE;
        }

        private static void destroyDebugUtilsMessengerEXT(VkInstance instance, long debugMessenger, VkAllocationCallbacks allocationCallbacks) {
            if(vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) { // 這個 extension 是否存在
                vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks);
            }
        }

        // ======= FIELDS ======= //

        // a glfw window
        private long window;
        private VkInstance instance;
        private long debugMessenger;
        private VkPhysicalDevice physicalDevice;
        private VkDevice device;
        private VkQueue graphicsQueue;
        private VkQueue presentQueue;
        private long surface; // 可調整的會回傳一個pointer 不可調整只會回傳一個uint64 pointer在java端有各自的wrapper uint64則無
        private long swapChain;
        private List<Long> swapChainImages; // automatically cleared by swap chain
        private int swapChainFormat;
        private VkExtent2D swapChainExtent;
        private List<Long> swapChainImageViews;
        private long pipelineLayout;


        // ======= METHODS ======= //

        public void run() {
            initWindow();
            initVulkan();
            mainLoop();
            cleanup();
        }

        // Optional. Vulcan can do off window rendering
        private void initWindow() {

            if(!glfwInit()) {
                throw new RuntimeException("Cannot initialize GLFW");
            }

            // do not run opengl
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

            String title = getClass().getEnclosingClass().getSimpleName();

            window = glfwCreateWindow(WIDTH, HEIGHT, title, NULL, NULL);

            if(window == NULL) {
                throw new RuntimeException("Cannot create window");
            }
        }

        private void initVulkan() {
            createInstance();
            setupDebugMessenger(); // 這導致這個 debugMessenger不能debug createInstance的東西
            createSurface(); // surface 會影響 device 選擇
            pickPhysicalDevice();
            createLogicalDevice();
            createSwapChain();
            createImageView();
            createGraphicsPipeline();
        }

        private void mainLoop() {
            // error occurs or user presses X
            while(!glfwWindowShouldClose(window)) {
                glfwPollEvents();
            }

        }

        private void cleanup() {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            for (int i = 0; i < swapChainImageViews.size(); i++) {
                vkDestroyImageView(device, swapChainImageViews.get(i), null);
            }
            // swap chain 需要先被 destroy 因為他需要 device
            vkDestroySwapchainKHR(device, swapChain, null);
            // device 需要先銷毀因他需要 instance
            vkDestroyDevice(device, null); // physical device 不需要 free 因為 physical 是 vulcan 列舉出來記憶體位置給我們選 不是我們建立的
            if(ENABLE_VALIDATION_LAYERS) {
                destroyDebugUtilsMessengerEXT(instance, debugMessenger, null); // test remove
            }
            KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
            vkDestroyInstance(instance, null);
            // juts cleanup everything when closing window
            glfwDestroyWindow(window);
            glfwTerminate();
        }

        // create Vulcan Instance
        private void createInstance() {
            if(ENABLE_VALIDATION_LAYERS && !checkValidationLayerSupport()) {
                throw new RuntimeException("Validation requested but not supported");
            }

            // Java 的 primitive 是存在 stack 上但是物件是存在 heap 上 (畢竟要製造物件必須要使用new)
            // 這個造在 heap 上的物件隨時都會被 garbage collector 移走，因此需要弄個 stack 並且把物件存上去
            try(MemoryStack stack = stackPush()) {
                // calloc 會清零 C 用的 malloc 不會 清零
                // 等效 C 語言中的 struct, VkApplicationInfo appInfo = {};
                // 如果用 malloc 則是 C 語言中的, VkApplicationInfo appInfo;
                // 清零因為有些指標的設定像是 pNext (定義更多的東西extension的位置) 才是 nullptr
                VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);

                appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO); // sType is required // 這個 application info vulcan 不 care
                appInfo.pApplicationName(stack.UTF8Safe("Hello Triangle"));
                appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
                appInfo.pEngineName(stack.UTF8Safe("No Engine"));
                appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
                appInfo.apiVersion(VK_API_VERSION_1_0);

                VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO); // 這個註解掉 VK_LAYER_KHRONOS_validation 會叫
                createInfo.pApplicationInfo(appInfo);

                // 讓 vulcan 知道 glfw 有螢幕可以用 和其他 extension (像是 callback 的 debug utils)
                createInfo.ppEnabledExtensionNames(getRequiredExtensions(stack));
                // 不告知錯 直接 crash

                if (ENABLE_VALIDATION_LAYERS) {
                    PointerBuffer buffer = stack.mallocPointer(VALIDATION_LAYERS.size()); // 等效 std::vector<char*> 指向char*陣列的Pointer
                    VALIDATION_LAYERS.stream().map(stack::UTF8).forEach(buffer::put); // Java 是用 UTF16 C 用 UTF8
                    /*
                    buffer (PointerBuffer)
                      │
                      ▼
                    [ ptr0 | ptr1 | ptr2 | ... ]
                        │
                        ▼
                      "VK_LAYER_KHRONOS_validation" (UTF-8 in stack)
                     */
                    buffer.rewind(); // 因為 buffer::put 會把 buffer 指向的位置向後移所以把她條回原本的位置
                    createInfo.ppEnabledLayerNames(buffer); // layer => validationLayer

                    VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack); // 不需要放在外面因為 stack 是看 try block 去決定要不要 free 的
                    populateDebugMessengerCreateInfo(debugCreateInfo); // callback 定義在這裡
                    createInfo.pNext(debugCreateInfo.address()); // 這樣vkCreateInstance就會用到 callback 了
                }

                // allocate 一個 pointer 以後用來存 instance, 原版的 C 直接用 & 但是 java 沒有這東西
                PointerBuffer instancePtr = stack.mallocPointer(1); // 申請 1 個「指標（Pointer）」 的空間

                // 跑下面程式 instancePtr 就有你的 instance 了
                if(vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create instance");
                }

                // 這個 tutorial 沒有 會 out of memory
                instance = new VkInstance(instancePtr.get(0), createInfo);

            }
        }

        private boolean checkValidationLayerSupport() {
            try(MemoryStack stack = stackPush()) { // 開始 C++ 操作 因為 java 的物件都是放在 heap 我們希望它跟 C++ 一樣是放在 stack
                IntBuffer layerCount = stack.ints(0); // 這裡的 0 是初始化為 0 (等效 mallocInt(1).put(0, x)) (int[] buffer])
                vkEnumerateInstanceLayerProperties(layerCount, null); // 將層數放進 layerCount
                VkLayerProperties.Buffer availableLayers = VkLayerProperties.malloc(layerCount.get(0), stack);
                vkEnumerateInstanceLayerProperties(layerCount, availableLayers); // 拿到 availableLayers
                Set<String> availableLayerNames = availableLayers.stream()
                        .map(VkLayerProperties::layerNameString)
                        .collect(toSet());
                System.out.println("All layers available:");
                for (String name : availableLayerNames) {
                    System.out.println(name);
                }
                return availableLayerNames.containsAll(VALIDATION_LAYERS);
            }
        }

        private PointerBuffer getRequiredExtensions(MemoryStack stack) {

            PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

            if(ENABLE_VALIDATION_LAYERS) {
                // PointerBuffer => void* buffer[n] 放在 stack
                PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);
                // 一個 overload 把 另一個 void* buffer[] 裡的值全部一個一個倒進 extensions 裡
                extensions.put(glfwExtensions);
                // 單倒一個 ByteBuffer
                extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)); // 專門決定 callback 位置的 extension
                System.out.println("Printing extensions: Extension Capacity: " + extensions.capacity());
                for (int i = 0; i < extensions.capacity(); i++) {
//                    ByteBuffer byteBuffer = memByteBuffer(extensions.get(i), 256); // 去 void* buffer[] 找三個pointer 根據其位址抓256個byte的值 變成個 void* 但多存其大小256
                    System.out.println(memUTF8(extensions.get(i))); // 去 void* buffer[] 找三個pointer根據其位址向後印出所有UTF8直到遇到\0
                }
                System.out.println("===========");
                // Rewind the buffer before returning it to reset its position back to 0
                return extensions.rewind();
            }

            return glfwExtensions;
        }

        private void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo) {
            debugCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
            debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
            debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
            debugCreateInfo.pfnUserCallback(HelloTriangleApplication::debugCallback);
        }

        private void setupDebugMessenger() {
            if (!ENABLE_VALIDATION_LAYERS) {
                return;
            }

            // 好像是專門為了用 pass in reference 使用 stack
            try(MemoryStack stack = stackPush()) {

                VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);

                populateDebugMessengerCreateInfo(createInfo);
                // Buffer 都是為了可以 pass in reference
                LongBuffer pDebugMessenger = stack.longs(VK_NULL_HANDLE); // 做一個 long 初始值是 0 存進 LongBuffer (long buffer[])

                if(createDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger) != VK_SUCCESS) { // pass in reference
                    throw new RuntimeException("Failed to set up debug messenger");
                }
                // 從 long buffer[] 拿第一個long值
                debugMessenger = pDebugMessenger.get(0);
            }
        }

        private static int createDebugUtilsMessengerEXT(VkInstance instance, VkDebugUtilsMessengerCreateInfoEXT createInfo,
                                                        VkAllocationCallbacks allocationCallbacks, LongBuffer pDebugMessenger) {

            if(vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) { // vkCreateDebug.. 是個 extension function 看看他存不存在
                return vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger); // 這 function 需要 instance 導致 createInstance 與 destroyInstance 不會抓到
            }

            return VK_ERROR_EXTENSION_NOT_PRESENT;
        }


        private void pickPhysicalDevice() {
            try (MemoryStack stack = stackPush()) { // push stack pointer 等到 try block 結束後會自動 pop 回來 free stack memory
                IntBuffer deviceCount = stack.ints(0); // 預設一個準備 reference 的 int 初始直 0
                vkEnumeratePhysicalDevices(instance, deviceCount, null);
                if (deviceCount.get(0) == 0) {
                    throw new RuntimeException("Failed to find GPUs with Vulkan support");
                }
                PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
                vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);
                System.out.println("Found " + deviceCount.get(0) + " Physical Devices");
                for (int i = 0; i < ppPhysicalDevices.capacity(); i++) {
                    VkPhysicalDevice device = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance); // 拿到記憶體位置後 wrap 成一個 java 物件指向 vulcan device
                    if (isDeviceSuitable(device)) {
                        physicalDevice = device;
                        System.out.println("Found Suitable with graphics support device at memory location " + device.address());
                        break;
                    }
                }
            } // try 結束後 free 掉 buffer 但存有 device 的 java 物件還在

            if (physicalDevice == null) {
                throw new RuntimeException("failed to find a suitable GPU!");
            }
        }

        // 從 device 找所有的 queue family 看看他們有沒有 graphics 與 present 功能
        private boolean isDeviceSuitable(VkPhysicalDevice device) {
            // old way
//            try (MemoryStack stack = stackPush()) {
                // 因為 Vk.. 是 struct 不是一個物件的 memory location 所以直接 malloc 就行
//                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
//                vkGetPhysicalDeviceProperties(device, properties);
//                VkPhysicalDeviceFeatures feature = VkPhysicalDeviceFeatures.malloc(stack);
//                vkGetPhysicalDeviceFeatures(device, feature);
//
//                return properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU &&
//                        feature.geometryShader();
//            }
            try (MemoryStack stack = stackPush()) {
                // using queue family 檢查是否可以做 present 跟 graphical
                QueueFamilyIndices indices = findQueueFamilyIndices(device);
                // device extensions (swap chain extension)
                boolean extensionSupported = checkDeviceExtensionSupport(device);
                if (extensionSupported) {
                    System.out.println("DEVICE EXTENSIONS all supported");
                }
                // 檢查是否 swap chain device 可以完成
                boolean swapChainAdequate = false;
                if (extensionSupported) {
                    SwapChainSupportDetails details = querySwapChainSupport(stack, device);
                    if (details.formats.capacity() > 0 && details.presentModes.capacity() > 0) swapChainAdequate = true;
                }
                return indices.isComplete() && extensionSupported && swapChainAdequate;
            }
        }

        private boolean checkDeviceExtensionSupport(VkPhysicalDevice device) {
            try (MemoryStack stack = stackPush()) {
                IntBuffer propertyCountBuffer = stack.callocInt(1);
                vkEnumerateDeviceExtensionProperties(device, (String)null, propertyCountBuffer, null);
                VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(propertyCountBuffer.get(0), stack);
                vkEnumerateDeviceExtensionProperties(device, (String)null, propertyCountBuffer, properties);

                return properties.stream()
                        .map(VkExtensionProperties::extensionNameString)
                        .collect(toSet())
                        .containsAll(DEVICE_EXTENSIONS);

                // DEVICE EXTENSION 是 properties 的子集合
            }
        }

        private QueueFamilyIndices findQueueFamilyIndices(VkPhysicalDevice device) {
            QueueFamilyIndices indices = new QueueFamilyIndices();
            try (MemoryStack stack = stackPush()) {
                IntBuffer queueFamilyCount = stack.ints(0);
                vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);
                VkQueueFamilyProperties.Buffer propertiesBufferStruct = VkQueueFamilyProperties.malloc(queueFamilyCount.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, propertiesBufferStruct); // 把 GPU 的 driver 裡的 Queue Family 的屬性拿出來用

                for (int i = 0; i < propertiesBufferStruct.capacity(); i++) {
                    if ((propertiesBufferStruct.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        indices.graphicsFamily = i;
                        System.out.println("Found Family ID: " + i + " that can do graphics");
                    }

                    IntBuffer supportsKHR = stack.callocInt(1);
                    vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supportsKHR);
                    if (supportsKHR.get(0) == VK_TRUE) {
                        indices.presentFamily = i;
                        System.out.println("Found Family ID: " + i + " that can do present");
                    }

                    if (indices.isComplete()) break;
                }
            }

            return indices;
        }

        private void createLogicalDevice() {
            QueueFamilyIndices indices = findQueueFamilyIndices(physicalDevice);

            try (MemoryStack stack = stackPush()) {
                Set<Integer> uniqueQueueFamilies = new HashSet<>();
                uniqueQueueFamilies.add(indices.graphicsFamily);
                uniqueQueueFamilies.add(indices.presentFamily);
                Iterator<Integer> uniqueQueueFamiliesIterator = uniqueQueueFamilies.iterator();

                VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.size(), stack);

                for (int i = 0; i < uniqueQueueFamilies.size(); i++) {
                    VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                    queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO); // sType 讓 pNext 可以知道他是什麼擴充 pNext 是 void* 只是個萬用指標
                    queueCreateInfo.queueFamilyIndex(uniqueQueueFamiliesIterator.next());
                    queueCreateInfo.pQueuePriorities(stack.floats(1.0f)); // 可以用 stack.floats(0.5f, 1.0f, 1.5f) 這樣就有三個queue 各自有不同的 priority
                }
                queueCreateInfos.rewind();

                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack); // leave it empty

                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
                createInfo.pQueueCreateInfos(queueCreateInfos);
                createInfo.pEnabledFeatures(features);

                // 有 instance extension 跟 device extension
                // device extension 專門做 GPU 渲染的 extension
                // 這裡的 swap chain 跟渲染有關 所以是 device 的
                PointerBuffer deviceExtensionsBuffer = stack.callocPointer(DEVICE_EXTENSIONS.size());
                DEVICE_EXTENSIONS.stream().map(stack::UTF8).forEach(deviceExtensionsBuffer::put);
                deviceExtensionsBuffer.rewind();
                createInfo.ppEnabledExtensionNames(deviceExtensionsBuffer);

                // optional, newer vulcan versions ignores this
                if (ENABLE_VALIDATION_LAYERS) {
                    PointerBuffer layers = stack.callocPointer(VALIDATION_LAYERS.size());
                    for (String layerName : VALIDATION_LAYERS) {
                        ByteBuffer bufferLayerName = stack.UTF8(layerName);
                        layers.put(bufferLayerName);
                    }
                    layers.rewind();
                    createInfo.ppEnabledLayerNames(layers);
                }

                PointerBuffer deviceBuffer = stack.callocPointer(1);
                if (vkCreateDevice(physicalDevice, createInfo, null, deviceBuffer) != VK_SUCCESS) { // Vulcan API 製造出來的 object 的 memory location
                    throw new RuntimeException("failed to create logical device!");
                }

                device = new VkDevice(deviceBuffer.get(0), physicalDevice, createInfo); // 存進這個 java 的 wrapper
                PointerBuffer graphicsQueueBuffer = stack.callocPointer(1);
                PointerBuffer presentQueueBuffer = stack.callocPointer(1);
                vkGetDeviceQueue(device, indices.graphicsFamily, 0, graphicsQueueBuffer); // queueIndex 決定使用 logical queue 的第幾個 queue
                graphicsQueue = new VkQueue(graphicsQueueBuffer.get(0), device);
                vkGetDeviceQueue(device, indices.presentFamily, 0, presentQueueBuffer);
                presentQueue = new VkQueue(presentQueueBuffer.get(0), device);
            }
        }

        private void createSurface() {
            try (MemoryStack stack = stackPush()) {
                LongBuffer surfaceBuffer = stack.callocLong(1);
                if (glfwCreateWindowSurface(instance, window, null, surfaceBuffer) != VK_SUCCESS) {
                    throw new RuntimeException("failed to create window surface!");
                }

                surface = surfaceBuffer.get(0);
            }
        }

        private SwapChainSupportDetails querySwapChainSupport(MemoryStack stack, VkPhysicalDevice device) {
            SwapChainSupportDetails details = new SwapChainSupportDetails();
            details.capabilities = VkSurfaceCapabilitiesKHR.calloc(1, stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities.get(0));
            IntBuffer formatCount = stack.callocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, formatCount, null);
            details.formats = VkSurfaceFormatKHR.calloc(formatCount.get(0), stack);
            if (formatCount.get(0) > 0) {
                vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, formatCount, details.formats);
            }
            IntBuffer presentModeCount = stack.callocInt(1);
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, presentModeCount, null);
            details.presentModes = stack.callocInt(presentModeCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, presentModeCount, details.presentModes);

            return details;
        }

        private VkSurfaceFormatKHR chooseSwapSurfaceFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
            for (VkSurfaceFormatKHR availableFormat : availableFormats) {
                if (availableFormat.format() == VK_FORMAT_B8G8R8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    System.out.println("surface format uses VK_FORMAT_B8G8R8_UNORM (not VK_FORMAT_B8G8R8A8_SRGB) and VK_COLOR_SPACE_SRGB_NONLINEAR_KHR");
                    return availableFormat;
                }
            }

            // 沒找到 RGBA 就直接拿第一個
            return availableFormats.get(0);
        }

        private int chooseSwapPresentMode(IntBuffer availablePresentModes) {
            for (int i = 0; i < availablePresentModes.capacity(); i++) {
                if (availablePresentModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR)
                    return VK_PRESENT_MODE_MAILBOX_KHR;
            }

            return VK_PRESENT_MODE_FIFO_KHR;
        }

        private VkExtent2D chooseSwapExtent(MemoryStack stack, VkSurfaceCapabilitiesKHR.Buffer capabilities) {
            if (capabilities.currentExtent().width() != 0xFFFFFFFF) { // 如果 gpu 要我們自己去找 width height
                return capabilities.currentExtent();
            }

            IntBuffer width = stack.callocInt(1);
            IntBuffer height = stack.callocInt(1);
            glfwGetFramebufferSize(window, width, height);

            VkExtent2D actualExtent = VkExtent2D.calloc(stack);
            // clamp width and height
            actualExtent.width(Math.min(Math.max(width.get(0), capabilities.minImageExtent().width()), capabilities.maxImageExtent().width()));
            actualExtent.height(Math.min(Math.max(height.get(0), capabilities.minImageExtent().height()), capabilities.maxImageExtent().height()));
            return actualExtent;
        }

        private void createSwapChain() {
            try (MemoryStack stack = stackPush()) {
                SwapChainSupportDetails details = querySwapChainSupport(stack, physicalDevice);

                VkSurfaceFormatKHR surfaceFormatKHR = chooseSwapSurfaceFormat(details.formats);
                int swapPresentMode = chooseSwapPresentMode(details.presentModes);
                VkExtent2D swapExtent = chooseSwapExtent(stack, details.capabilities);

                int imageCount =  details.capabilities.minImageCount() + 1;
                // maxImageCount = 0 => there is no max image count
                if (details.capabilities.maxImageCount() > 0 && imageCount > details.capabilities.maxImageCount()) {
                    imageCount = details.capabilities.maxImageCount();
                }

                VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
                createInfo.surface(surface);
                createInfo.minImageCount(imageCount);

                createInfo.imageColorSpace(surfaceFormatKHR.colorSpace());
                createInfo.imageExtent(swapExtent);
                createInfo.imageArrayLayers(1); // just 2d images, not 1 if create 3d movies
                createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT); // 變成顏色渲染目標
                System.out.println("DEBUG: IMAGE USGAE: " + createInfo.imageUsage());
                createInfo.imageFormat(surfaceFormatKHR.format());

                QueueFamilyIndices indices = findQueueFamilyIndices(physicalDevice);
                if (indices.graphicsFamily != indices.presentFamily) {
                    IntBuffer queueFamilyIndices = stack.ints(indices.graphicsFamily, indices.presentFamily);
                    createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT); // 不需要轉交所有權image這個檔案就可以在不同的queue 比較慢
                    createInfo.queueFamilyIndexCount(2);
                    createInfo.pQueueFamilyIndices(queueFamilyIndices);
                } else {
                    createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE); // image給其他queue時需要轉交所有權
                    // 同個 queue family 資料溝通不需要轉交所有權
                }
                createInfo.preTransform(details.capabilities.currentTransform()); // 不去旋轉任何東西
                createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
                createInfo.presentMode(swapPresentMode);
                createInfo.clipped(true); // 如果前面有視窗擋住 後面的 pixels 直接忽略
                createInfo.oldSwapchain(VK_NULL_HANDLE); // 如果 resize window 那 swap chain 需要換 目前不換

                LongBuffer swapChainBuffer = stack.callocLong(1);
                if (vkCreateSwapchainKHR(device, createInfo, null, swapChainBuffer) != VK_SUCCESS) {
                    throw new RuntimeException("failed to create swap chain!");
                }

                swapChain = swapChainBuffer.get(0);
                System.out.println("SwapChain created! handler: " + swapChain);

                IntBuffer imageCountBuffer = stack.callocInt(1);
                vkGetSwapchainImagesKHR(device, swapChain, imageCountBuffer, null);
                LongBuffer swapChainImagesBuffer = stack.callocLong(imageCountBuffer.get(0));
                vkGetSwapchainImagesKHR(device, swapChain, imageCountBuffer, swapChainImagesBuffer);
                System.out.println("SwapChain Image Count: " + imageCountBuffer.get(0)); // 一張 GPU 在畫 一張 等待顯示 一張顯示在螢幕上
                swapChainImages = new ArrayList<>(imageCountBuffer.get(0));
                for (int i = 0; i < imageCountBuffer.get(0); i++) {
                    swapChainImages.add(swapChainImagesBuffer.get(i));
                }

                swapChainFormat = surfaceFormatKHR.format();
                swapChainExtent = swapExtent;

            }
        }

        private void createImageView() {
            try (MemoryStack stack = stackPush()) {
                swapChainImageViews = new ArrayList<>(swapChainImages.size());
                for (int i = 0; i < swapChainImages.size(); i++) {
                    VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack);
                    createInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                    createInfo.image(swapChainImages.get(i));
                    createInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
                    createInfo.format(swapChainFormat);
                    VkComponentMapping mapping = VkComponentMapping.calloc(stack);
                    mapping.r(VK_COMPONENT_SWIZZLE_IDENTITY);
                    mapping.g(VK_COMPONENT_SWIZZLE_IDENTITY);
                    mapping.b(VK_COMPONENT_SWIZZLE_IDENTITY);
                    mapping.a(VK_COMPONENT_SWIZZLE_IDENTITY); // RGBA 順序不改
                    createInfo.components(mapping);
                    VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
                    range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT); // 他是顏色不是 depth 之類的
                    range.baseMipLevel(0);
                    range.levelCount(1); // 根據遠近 resolution 會變小的材質的數量 這裡是 1
                    range.baseArrayLayer(0);
                    range.layerCount(1); // 其他 VR 之類的用途 這裡是 2D 渲染 疊一張 RGB 就夠了
                    createInfo.subresourceRange(range);
                    LongBuffer view = stack.callocLong(1);
                    if (vkCreateImageView(device, createInfo, null, view) != VK_SUCCESS) {
                        throw new RuntimeException("failed to create image views!");
                    }
                    swapChainImageViews.add(view.get(0));
                }
            }
        }

        private long createShaderModule(MemoryStack stack, ByteBuffer codeBuffer) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(codeBuffer);
            LongBuffer shaderModuleBuffer = stack.callocLong(1);
            if (vkCreateShaderModule(device, createInfo, null, shaderModuleBuffer) != VK_SUCCESS) {
                throw new RuntimeException("failed to create shader module!");
            }
            return shaderModuleBuffer.get(0);
        }

        // 要記得 MemoryUtils memFree buffer
        private static ByteBuffer readShaderFile(MemoryStack stack, String filename) throws IOException {
            byte[] bytes = Files.readAllBytes(Paths.get(filename));
            ByteBuffer buffer = stack.calloc(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        }

        private void createGraphicsPipeline() {
            try (MemoryStack stack = stackPush()) {
                ByteBuffer vertCodeBuffer;
                ByteBuffer fragCodeBuffer;
                try {
                    vertCodeBuffer = readShaderFile(stack, "shaders/vert.spv");
                    fragCodeBuffer = readShaderFile(stack, "shaders/frag.spv");
                } catch (IOException e) {
                    System.out.println("Cannot read shader file");
                    return;
                }

                long vertShaderModule = createShaderModule(stack, vertCodeBuffer);
                long fragShaderModule = createShaderModule(stack, fragCodeBuffer);

                VkPipelineShaderStageCreateInfo.Buffer vertShaderStageInfo = VkPipelineShaderStageCreateInfo.calloc(1, stack);
                vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
                vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT); // vertex shader
                vertShaderStageInfo.module(vertShaderModule);
                vertShaderStageInfo.pName(stack.UTF8("main")); // shader file 裡的 entry point

                VkPipelineShaderStageCreateInfo.Buffer fragShaderStageInfo = VkPipelineShaderStageCreateInfo.calloc(1, stack);
                fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
                fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT); // fragment shader
                fragShaderStageInfo.module(fragShaderModule);
                fragShaderStageInfo.pName(stack.UTF8("main"));

                VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                shaderStages.put(vertShaderStageInfo);
                shaderStages.put(fragShaderStageInfo);
                shaderStages.rewind();

                // pipeline: input vertex -> vertex shader -> rasterization -> fragment shader -> color blending -> framebuffer
                // 一些可以在 pipeline 建立後可以改變的設定
//                IntBuffer dynamicStates = stack.ints(
//                        VK_DYNAMIC_STATE_VIEWPORT,
//                        VK_DYNAMIC_STATE_SCISSOR
//                );
//                VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack);
//                dynamicState.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
//                dynamicState.pDynamicStates(dynamicStates);

                // 先把模型的 vertex 讀進來
                VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
                vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
                VkVertexInputBindingDescription.Buffer vertexBindingDescriptionsBuffer = VkVertexInputBindingDescription.calloc(1, stack); // calloc to value 0 -> nullptr
                vertexInputInfo.pVertexBindingDescriptions(vertexBindingDescriptionsBuffer);
                VkVertexInputAttributeDescription.Buffer vertexInputAttributeDescriptionsBuffer = VkVertexInputAttributeDescription.calloc(1, stack);
                vertexInputInfo.pVertexAttributeDescriptions(vertexInputAttributeDescriptionsBuffer);

                VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
                inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
                inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST); // 輸進來的 vertex 都構成三角形 每三個 vertex 就是一個三角形
                inputAssembly.primitiveRestartEnable(false);

                // viewport defines the transformation to the framebuffer
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
                viewport.x(0.0f);
                viewport.y(0.0f);
                viewport.width(swapChainExtent.width());
                viewport.height(swapChainExtent.height());
                viewport.minDepth(0.0f);
                viewport.maxDepth(1.0f);

                // behaves like a scissor
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                VkOffset2D offset = VkOffset2D.calloc(stack);
                offset.x(0);
                offset.y(0);
                scissor.offset(offset);
                scissor.extent(swapChainExtent);

                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
                viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
                viewportState.scissorCount(1);
                viewportState.viewportCount(1);
                // 這裡不使用 dynamic state
                viewportState.pViewports(viewport);
                viewportState.pScissors(scissor);

                VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
                rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
                rasterizer.depthClampEnable(false); // if true near and far were clamped (用來做 shadow map 聽說好用)
                rasterizer.polygonMode(VK_POLYGON_MODE_FILL);
                // 如果用 VK_POLYGON_MODE_LINE 只會畫邊邊 如果用 VK_POLYGON_MODE_POINT 只會畫 點
                rasterizer.lineWidth(1.0f);
                rasterizer.cullMode(VK_CULL_MODE_BACK_BIT); // 把後面的面移除掉 cull 是淘汰的意思
                rasterizer.frontFace(VK_FRONT_FACE_CLOCKWISE); // clockwise 視為正面
                rasterizer.depthBiasEnable(false); // 用來 shadow mapping 用的微調

                // 聽說 antialiasing 會用到 先跳過
                VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
                multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
                multisampling.sampleShadingEnable(false);
                multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

                // color bending 會把 fragment shader 跟 framebuffer 的 pixel color blend 在一起
                // 這裡完全不開直接把 fragmentation shader 輸出直接丟進 framebuffer
                // 這個設定跟 buffer 有關 多個buffer 就有多個設定
                VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
                // 刪除一個 那個頻道的值就永遠不變 只會在初始值
                colorBlendAttachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
                colorBlendAttachment.blendEnable(false);
                // global settings
                VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
                colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
                colorBlending.logicOpEnable(false);
                colorBlending.attachmentCount(1);
                colorBlending.pAttachments(colorBlendAttachment);

                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
                LongBuffer pipelineLayoutBuffer = stack.callocLong(1);
                pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
                if (vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipelineLayoutBuffer) != VK_SUCCESS) {
                    throw new RuntimeException("failed to create pipeline layout!");
                }

                pipelineLayout = pipelineLayoutBuffer.get(0);

                vkDestroyShaderModule(device, vertShaderModule, null);
                vkDestroyShaderModule(device, fragShaderModule, null);
            }

        }

    }

    public static void main(String[] args) {

        HelloTriangleApplication app = new HelloTriangleApplication();
        app.run();
    }
}