package org.example;

// glfw for showing something on the screen. Optional
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.create;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
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

        private class QueueFamilyIndices {
            public Integer graphicsFamily; // 專門做 graphics 的 indice // 像是 c++ 的 optional 如果 null 則沒有值

            public boolean isComplete() {
                return graphicsFamily != null;
            }
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
            pickPhysicalDevice();
            createLogicalDevice();
        }

        private void mainLoop() {
            // error occurs or user presses X
            while(!glfwWindowShouldClose(window)) {
                glfwPollEvents();
            }

        }

        private void cleanup() {
            // device 需要先銷毀因他需要 instance
            vkDestroyDevice(device, null); // physical device 不需要 free 因為 physical 是 vulcan 列舉出來記憶體位置給我們選 不是我們建立的
            if(ENABLE_VALIDATION_LAYERS) {
                destroyDebugUtilsMessengerEXT(instance, debugMessenger, null); // test remove
            }
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
                        .collect(Collectors.toSet());
                System.out.println("All tests available:");
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
            // using queuefamily
            QueueFamilyIndices indices = findQueueFamilyIndices(device);
            return indices.isComplete();
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

                    if (indices.isComplete()) break;
                }
            }

            return indices;
        }

        private void createLogicalDevice() {
            QueueFamilyIndices indices = findQueueFamilyIndices(physicalDevice);

            try (MemoryStack stack = stackPush()) {
                VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
                queueCreateInfos.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO); // sType 讓 pNext 可以知道他是什麼擴充 pNext 是 void* 只是個萬用指標
                queueCreateInfos.queueFamilyIndex(indices.graphicsFamily);
                queueCreateInfos.pQueuePriorities(stack.floats(1.0f)); // 可以用 stack.floats(0.5f, 1.0f, 1.5f) 這樣就有三個queue 各自有不同的 priority

                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack); // leave it empty

                VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
                createInfo.pQueueCreateInfos(queueCreateInfos);
                createInfo.pEnabledFeatures(features);

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
                vkGetDeviceQueue(device, indices.graphicsFamily, 0, graphicsQueueBuffer);
                graphicsQueue = new VkQueue(graphicsQueueBuffer.get(0), device);
            }
        }

    }

    public static void main(String[] args) {

        HelloTriangleApplication app = new HelloTriangleApplication();
        app.run();
    }
}