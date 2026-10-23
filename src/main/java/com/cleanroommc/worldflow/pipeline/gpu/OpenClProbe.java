package com.cleanroommc.worldflow.pipeline.gpu;

import com.cleanroommc.worldflow.WorldFlow;

/**
 * Reflective probe for an OpenCL 1.2+ runtime. Java 8 compatible: no compile-time
 * dependency on LWJGL or any Panama/incubator API, so the same jar loads on Forge
 * (Java 8) and Cleanroom (Java 25).
 *
 * <p>This is detection only. The CPU wavefront remains the production path and the
 * fallback whenever the probe fails.</p>
 */
public final class OpenClProbe {

    private static volatile boolean available;
    private static volatile String deviceName = "none";
    private static volatile boolean probed;

    private OpenClProbe() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String getDeviceName() {
        return deviceName;
    }

    public static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> platformClass = Class.forName("org.lwjgl.opencl.CLPlatform");
            Object platforms = platformClass.getMethod("getPlatforms").invoke(null);
            if (!(platforms instanceof java.util.List) || ((java.util.List<?>) platforms).isEmpty()) {
                WorldFlow.LOGGER.info("[WorldFlow] OpenCL probe: no platform found; CPU wavefront only.");
                return;
            }
            Object platform = ((java.util.List<?>) platforms).get(0);
            // CL_DEVICE_TYPE_ALL is 0xFFFFFFFF, i.e. -1 as a Java int.
            Object devices = platformClass.getMethod("getDevices", int.class).invoke(platform, -1);
            if (!(devices instanceof java.util.List) || ((java.util.List<?>) devices).isEmpty()) {
                WorldFlow.LOGGER.info("[WorldFlow] OpenCL probe: platform present but no device; CPU wavefront only.");
                return;
            }
            Object device = ((java.util.List<?>) devices).get(0);
            String name;
            try {
                // CL_DEVICE_NAME = 0x102B
                Object value = device.getClass().getMethod("getInfoString", int.class).invoke(device, 0x102B);
                name = String.valueOf(value);
            } catch (Throwable ignored) {
                name = device.toString();
            }
            deviceName = name;
            available = true;
            WorldFlow.LOGGER.info("[WorldFlow] OpenCL probe: device '{}' detected; GPU pre-primer available (opt-in).", name);
        } catch (Throwable t) {
            WorldFlow.LOGGER.info("[WorldFlow] OpenCL probe: no usable runtime ({}); CPU wavefront only.", t.getClass().getSimpleName());
        }
    }
}
