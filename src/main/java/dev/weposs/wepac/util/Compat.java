package dev.weposs.wepac.util;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Map;

/**
 * Version-compatibility helpers.
 *
 * Bukkit 1.20.5+ moved Enchantment / PotionEffectType to registries and Paper
 * turned InventoryView into an interface. A plugin compiled against one version
 * can hit NoSuchFieldError / IncompatibleClassChangeError at runtime on another.
 * These helpers resolve enchantments/effects by their stable string KEY (iterating
 * what the item/player actually has) and never touch version-specific static
 * fields, and every lookup is wrapped so a failure degrades gracefully instead
 * of crashing the plugin.
 */
public final class Compat {

    private Compat() {
    }

    /** Level of the first matching enchantment on the item (0 if none / on error). */
    public static int enchantLevel(ItemStack item, String... keyNames) {
        if (item == null || !item.hasItemMeta()) return 0;
        try {
            for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
                Enchantment en = e.getKey();
                if (en == null) continue;
                String k = enchantKey(en);
                for (String want : keyNames) {
                    if (want.equalsIgnoreCase(k)) {
                        return e.getValue() == null ? 0 : e.getValue();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public static boolean hasEnchant(ItemStack item, String... keyNames) {
        return enchantLevel(item, keyNames) > 0;
    }

    private static String enchantKey(Enchantment en) {
        try {
            return en.getKey().getKey();
        } catch (Throwable t) {
            try {
                return en.getName().toLowerCase(Locale.ROOT);
            } catch (Throwable t2) {
                return "";
            }
        }
    }

    /** True if the player has an active effect matching any of the given keys. */
    public static boolean hasEffect(Player player, String... keyNames) {
        return effectAmplifier(player, keyNames) >= 0;
    }

    /** Amplifier+1 (level) of the first matching active effect, or -1 if absent. */
    public static int effectAmplifier(Player player, String... keyNames) {
        if (player == null) return -1;
        try {
            for (PotionEffect eff : player.getActivePotionEffects()) {
                if (eff == null) continue;
                String k = effectKey(eff.getType());
                for (String want : keyNames) {
                    if (want.equalsIgnoreCase(k)) return eff.getAmplifier() + 1;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String effectKey(PotionEffectType t) {
        if (t == null) return "";
        try {
            return t.getKey().getKey();
        } catch (Throwable e) {
            try {
                return t.getName().toLowerCase(Locale.ROOT);
            } catch (Throwable e2) {
                return "";
            }
        }
    }

    // Common key aliases across versions (registry renames in 1.20.5+)
    public static final String[] JUMP = {"jump", "jump_boost"};
    public static final String[] HASTE = {"haste", "fast_digging"};
    public static final String[] SPEED = {"speed"};
    public static final String[] LEVITATION = {"levitation"};
    public static final String[] SLOW_FALLING = {"slow_falling"};
    public static final String[] DOLPHINS = {"dolphins_grace"};
    public static final String[] EFFICIENCY = {"efficiency", "dig_speed"};
    public static final String[] DEPTH_STRIDER = {"depth_strider"};
    public static final String[] SOUL_SPEED = {"soul_speed"};
}
