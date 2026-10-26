package dev.weposs.wepac.util;

import java.util.UUID;

/**
 * Soft Floodgate probe (Geyser/Bedrock players).
 *
 * Bedrock clients drive rotations with touch sticks and controllers: their
 * yaw/pitch deltas carry NO mouse-step quantisation, so GCD tests collapse,
 * and their GUI does not capture the camera the same way. Judging them by
 * Java-mouse heuristics produces a stream of false positives on every
 * Geyser server - so when Floodgate is installed and knows the player,
 * mouse-specific detectors exempt them (reach/physics checks still apply:
 * those are input-device agnostic).
 *
 * Pure reflection: no compile-time or runtime dependency on Floodgate.
 */
public final class Bedrock {

    private static boolean probed = false;
    private static Object api; // FloodgateApi instance, or null

    private Bedrock() {
    }

    public static boolean isBedrock(UUID id) {
        if (!probed) {
            probed = true;
            try {
                Class<?> cls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                api = cls.getMethod("getInstance").invoke(null);
            } catch (Throwable ignored) {
                api = null; // Floodgate not installed - everyone is Java-edition
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) api.getClass()
                    .getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(api, id);
        } catch (Throwable t) {
            return false;
        }
    }
}
