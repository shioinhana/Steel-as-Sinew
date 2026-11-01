package org.fsrpg.dev;

/** Enumerates monitors without creating or focusing any window. */
public final class MonitorProbe {
    public static void main(String[] ignored) throws Exception {
        Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
        if (!(boolean) glfw.getMethod("glfwInit").invoke(null)) throw new IllegalStateException("GLFW initialization failed");
        try {
            Class<?> win32 = Class.forName("org.lwjgl.glfw.GLFWNativeWin32");
            Object monitors = glfw.getMethod("glfwGetMonitors").invoke(null);
            for (int i = 0; i < (int) monitors.getClass().getMethod("limit").invoke(monitors); i++) {
                long handle = (long) monitors.getClass().getMethod("get", int.class).invoke(monitors, i);
                int[] mx = new int[1], my = new int[1];
                glfw.getMethod("glfwGetMonitorPos", long.class, int[].class, int[].class).invoke(null, handle, mx, my);
                System.out.printf("Monitor %d: adapter=%s monitor=%s origin=(%d,%d)%n", i,
                        win32.getMethod("glfwGetWin32Adapter", long.class).invoke(null, handle),
                        win32.getMethod("glfwGetWin32Monitor", long.class).invoke(null, handle), mx[0], my[0]);
            }
            long selected = (long) glfw.getMethod("glfwGetPrimaryMonitor").invoke(null);
            String adapter = (String) win32.getMethod("glfwGetWin32Adapter", long.class).invoke(null, selected);
            int[] x = new int[1], y = new int[1];
            glfw.getMethod("glfwGetMonitorPos", long.class, int[].class, int[].class).invoke(null, selected, x, y);
            System.out.printf("Verified test monitor %s at (%d,%d), no window opened.%n", adapter, x[0], y[0]);
        } finally { glfw.getMethod("glfwTerminate").invoke(null); }
    }
}
