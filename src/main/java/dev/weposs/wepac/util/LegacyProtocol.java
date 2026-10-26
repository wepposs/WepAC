package dev.weposs.wepac.util;

import java.util.UUID;

/**
 * Soft ViaVersion probe (E2). A 1.8-protocol player (via ViaVersion on a
 * modern server) has NO attack cooldown: getAttackCooldown() is always 0,
 * so charge-based detectors (KillAura rate gate, CooldownLock) are blind or
 * misleading for them, and the AI charge feature reads as an anomaly against
 * a 1.9+ population. When Via is installed and reports protocol <= 47, those
 * detectors exempt the player (movement/physics checks still apply - gravity
 * did not change in 1.9).
 *
 * Pure reflection: no compile-time or runtime dependency on ViaVersion.
 */
public final class LegacyProtocol {

    /** 1.8 protocol version; anything <= this predates the attack cooldown. */
    public static final int PROTOCOL_1_8 = 47;

    private static boolean probed = false;
    private static Object api; // ViaAPI instance, or null

    private LegacyProtocol() {
    }

    /** Protocol version of the player's client, or -1 when unknown (no Via). */
    public static int playerVersion(UUID id) {
        if (!probed) {
            probed = true;
            api = tryApi("com.viaversion.viaversion.api.Via");   // Via 4.x+
            if (api == null) {
                api = tryApi("us.myles.ViaVersion.api.Via");      // Via legacy
            }
        }
        if (api == null) return -1;
        try {
            return (Integer) api.getClass()
                    .getMethod("getPlayerVersion", UUID.class)
                    .invoke(api, id);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object tryApi(String className) {
        try {
            return Class.forName(className).getMethod("getAPI").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
