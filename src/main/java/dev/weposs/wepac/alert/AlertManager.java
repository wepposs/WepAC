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

    public void send(Player flagged, CheckType type, int vl, String info) {
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
        String msg = plugin.config().alertFormat()
                .replace("{prefix}", plugin.config().prefix())
                .replace("{player}", player.getName())
                .replace("{check}", type.displayName())
                .replace("{vl}", String.valueOf(vl))
                .replace("{ping}", String.valueOf(player.getPing()))
                .replace("{ai}", ai)
                .replace("{info}", info == null ? "" : info);
        return WepAC.color(msg);
    }
}
