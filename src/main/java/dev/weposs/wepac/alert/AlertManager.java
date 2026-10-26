package dev.weposs.wepac.alert;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Staff notifications. Each staff member can toggle alerts with /ac alerts. */
public final class AlertManager {

    private final WepAC plugin;
    private final Set<UUID> disabled = new HashSet<>();
    /**
     * Per player+check message throttle (main thread only). A reach cheat in a
     * fight flags on EVERY hit; without this the staff chat is dozens of
     * messages per second. VL bookkeeping and punishments are NOT affected -
     * only the chat/console message is suppressed.
     */
    private final java.util.Map<String, Long> lastSent = new java.util.HashMap<>();

    public AlertManager(WepAC plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player staff) {
        if (disabled.remove(staff.getUniqueId())) return true;
        disabled.add(staff.getUniqueId());
        return false;
    }

    public boolean enabledFor(Player staff) {
        return !disabled.contains(staff.getUniqueId());
    }

    /** Drop per-player state on quit so neither set grows without bound. */
    public void forget(UUID id) {
        disabled.remove(id);
        String prefix = id + "|";
        lastSent.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void send(Player flagged, CheckType type, int vl, String info) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.config().alertCooldownMs;
        if (cooldown > 0) {
            String key = flagged.getUniqueId() + "|" + type.name();
            Long prev = lastSent.get(key);
            if (prev != null && now - prev < cooldown) return; // throttled message only
            if (lastSent.size() > 2048) {
                lastSent.values().removeIf(t -> now - t > 60_000L);
            }
            lastSent.put(key, now);
        }
        String message = format(flagged, type, vl, info,
                String.format(Locale.ROOT, "%.2f", aiProbability(flagged)))
                + statusTag(flagged);
        String plain = ChatColor.stripColor(message);

        if (plugin.config().alertConsole()) {
            Bukkit.getConsoleSender().sendMessage(plain);
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.getUniqueId().equals(flagged.getUniqueId())) continue;
            if (!staff.hasPermission("wepac.alerts")) continue;
            if (!enabledFor(staff)) continue;
            staff.sendMessage(message);
        }
    }

    /** [НА УЧЁТЕ] / [В РОЗЫСКЕ] tag appended to alerts. */
    private String statusTag(Player player) {
        return switch (plugin.records().status(player.getUniqueId())) {
            case WATCH -> WepAC.color(" &8[&6НА УЧЁТЕ&8]");
            case WANTED -> WepAC.color(" &8[&c&lВ РОЗЫСКЕ&8]");
            default -> "";
        };
    }

    /**
     * The anti-cheat SCREAMS into the staff console and chat.
     * Used when the police-record review is sure (or almost sure) a player is a cheater.
     */
    public void rage(Player player, String reason) {
        String base = plugin.config().rageMessage.replace("%player%", player.getName());
        String colored = WepAC.color(base);
        String plain = ChatColor.stripColor(colored) + " (" + reason + ")";
        int times = Math.max(1, plugin.config().rageRepeat);
        for (int i = 0; i < times; i++) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.hasPermission("wepac.alerts")) continue;
                if (!enabledFor(staff)) continue;
                staff.sendMessage(colored);
            }
            Bukkit.getConsoleSender().sendMessage(plain);
        }
    }

    public void sendRaw(Player staff, String message) {
        staff.sendMessage(plugin.config().prefix() + message);
    }

    public void broadcastRaw(String message) {
        Bukkit.broadcast(plugin.config().prefix() + message, "wepac.alerts");
    }

    private double aiProbability(Player player) {
        return plugin.data().get(player).aiProbability;
    }

    private String format(Player player, CheckType type, int vl, String info, String ai) {
        // D5: staff need the location to teleport in and adjudicate the flag
        org.bukkit.Location loc = player.getLocation();
        String msg = plugin.config().alertFormat()
                .replace("{prefix}", plugin.config().prefix())
                .replace("{player}", player.getName())
                .replace("{check}", type.displayName())
                .replace("{vl}", String.valueOf(vl))
                .replace("{ping}", String.valueOf(player.getPing()))
                .replace("{ai}", ai)
                .replace("{world}", loc.getWorld() == null ? "?" : loc.getWorld().getName())
                .replace("{x}", String.format(Locale.ROOT, "%.0f", loc.getX()))
                .replace("{y}", String.format(Locale.ROOT, "%.0f", loc.getY()))
                .replace("{z}", String.format(Locale.ROOT, "%.0f", loc.getZ()))
                .replace("{info}", info == null ? "" : info);
        return WepAC.color(msg);
    }
}
