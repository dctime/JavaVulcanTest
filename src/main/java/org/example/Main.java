package org.example;

// glfw for showing something on the screen. Optional
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

public class Main {

    private static class HelloTriangleApplication {

        private static final int WIDTH = 800;
        private static final int HEIGHT = 600;

        // ======= FIELDS ======= //

        // a glfw window
        private long window;
        private VkInstance instance;

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
        }

        private void mainLoop() {
            // error occurs or user presses X
            while(!glfwWindowShouldClose(window)) {
                glfwPollEvents();
            }

        }

        private void cleanup() {
            // juts cleanup everything when closing window
            glfwDestroyWindow(window);
            glfwTerminate();
        }

        // create Vulcan Instance
        private void createInstance() {
            // Java 的 primitive 是存在 stack 上但是物件是存在 heap 上 (畢竟要製造物件必須要使用new)
            // 這個造在 heap 上的物件隨時都會被 garbage collector 移走，因此需要弄個 stack 並且把物件存上去
            try(MemoryStack stack = stackPush()) {
                // calloc 會清零 C 用的 malloc 不會 清零
                // 等效 C 語言中的 struct, VkApplicationInfo appInfo = {};
                // 如果用 malloc 則是 C 語言中的, VkApplicationInfo appInfo;
                // 清零因為有些指標的設定像是 pNext (定義更多的東西extension的位置) 才是 nullptr
                VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);

                appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO); // sType is required
                appInfo.pApplicationName(stack.UTF8Safe("Hello Triangle"));
                appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
                appInfo.pEngineName(stack.UTF8Safe("No Engine"));
                appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
                appInfo.apiVersion(VK_API_VERSION_1_0);

                VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
                createInfo.pApplicationInfo(appInfo);

                // 讓  vulcan 知道 glfw 有螢幕可以用
                createInfo.ppEnabledExtensionNames(glfwGetRequiredInstanceExtensions());
                // 不告知錯 直接 crash
//                createInfo.ppEnabledLayerNames(null);

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

    }

    public static void main(String[] args) {

        HelloTriangleApplication app = new HelloTriangleApplication();

        app.run();
    }
}