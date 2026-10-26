package dev.weposs.wepac.punish;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes configured punishment commands - but only when the anti-cheat is
 * CONVINCED (see confirm section): kick/ban stages are deferred and retried
 * on every further VL until confirmation passes. Confirmed strikes feed the
 * AI training data and the police-record system.
 */
public final class PunishmentManager {

    private final WepAC plugin;
    private final Map<String, Long> deferNotifyCooldown = new ConcurrentHashMap<>();

    public PunishmentManager(WepAC plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true when the stage was executed (or was alert-only) and must
     *         not be retried; false when it was deferred for lack of confirmation.
     */
    public boolean tryExecute(Player player, CheckType type, int vl, String command) {
        if (command == null || command.isEmpty()) return true; // alert-only stage

        String cmd = command
                .replace("%player%", player.getName())
                .replace("%check%", type.displayName())
                .replace("%vl%", String.valueOf(vl));

        boolean harsh = isHarsh(cmd);
        if (harsh && plugin.config().confirmEnabled && !confirmed(player, type)) {
            notifyDeferred(player, type, vl);
            return false;
        }

        // Confirmed punishment -> training data for the AI (before the player leaves)
        plugin.ai().labelFromPunishment(player);

        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            if (!ok) {
                // bare Spigot has no /tempban: without this warning the strike and
                // the record would be written while nothing actually happened
                plugin.getLogger().warning("Punishment command was NOT executed "
                        + "(is a punishment plugin installed?): " + cmd);
            }
        });

        plugin.getLogger().info("Punished " + player.getName() + " [" + type.displayName()
                + " VL " + vl + "]: " + cmd);

        if (harsh) {
            plugin.records().addStrike(player, type, isBan(cmd));
        }
        return true;
    }

    /** Legacy direct execution (used by AI-only punishments). */
    public void execute(Player player, CheckType type, int vl, String command) {
        tryExecute(player, type, vl, command);
    }

    // ------------------------------------------------------------------

    private boolean confirmed(Player player, CheckType type) {
        PlayerData d = plugin.data().get(player);

        long distinct = d.violations.values().stream().filter(v -> v > 0).count();
        if (distinct >= plugin.config().confirmMinDistinctChecks) return true;

        int threshold = plugin.config().threshold(type);
        double recordMul = plugin.records().thresholdMultiplier(player.getUniqueId());
        if (d.vl(type) >= threshold * recordMul * plugin.config().confirmSingleVlMultiplier) return true;

        return d.aiProbability >= plugin.config().confirmAiProbability;
    }

    /** First word of a command, with leading '/' and any "namespace:" stripped. */
    private static String commandWord(String cmd) {
        String s = cmd.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        String head = space < 0 ? s : s.substring(0, space);
        int colon = head.indexOf(':');
        if (colon >= 0 && colon < head.length() - 1) {
            head = head.substring(colon + 1); // essentials:kick -> kick
        }
        return head;
    }

    /**
     * D1: verb lists are configuration, and UNKNOWN verbs are treated as harsh
     * (conservative). The old hardcoded list let custom aliases ("vban",
     * "sanction", pack-specific CMI commands) execute WITHOUT the confirmation
     * gate - unconfirmed auto-punishments.
     */
    private boolean isHarsh(String cmd) {
        String w = commandWord(cmd);
        return !plugin.config().safeVerbs.contains(w);
    }

    private static boolean isBan(String cmd) {
        String w = commandWord(cmd);
        return w.equals("ban") || w.equals("tempban")
                || w.equals("ban-ip") || w.equals("banip");
    }

    private void notifyDeferred(Player player, CheckType type, int vl) {
        String key = player.getUniqueId() + ":" + type.name();
        long now = System.currentTimeMillis();
        // bound the map: drop entries whose cooldown has long expired
        if (deferNotifyCooldown.size() > 512) {
            deferNotifyCooldown.values().removeIf(t -> now - t > 60_000L);
        }
        Long last = deferNotifyCooldown.get(key);
        if (last != null && now - last < 30_000L) return;
        deferNotifyCooldown.put(key, now);

        plugin.alerts().broadcastRaw(String.format(Locale.ROOT,
                "&e[вердикт не окончательный]&r &7%s &7(&c%s&7, VL %d): наказание отложено. "
                        + "Нужно: &f%d+ &7разных проверок, &fVL %.0f+ &7по этой, или уверенность ИИ &f%.0f%%&7.",
                player.getName(), type.displayName(), vl,
                plugin.config().confirmMinDistinctChecks,
                plugin.config().threshold(type)
                        * plugin.records().thresholdMultiplier(player.getUniqueId())
                        * plugin.config().confirmSingleVlMultiplier,
                plugin.config().confirmAiProbability * 100));
    }
}
