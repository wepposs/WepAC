package dev.weposs.wepac.check;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/** Base class for all heuristic detection modules. */
public abstract class Check implements Listener {

    protected final WepAC plugin;
    private final CheckType type;

    protected Check(WepAC plugin, CheckType type) {
        this.plugin = plugin;
        this.type = type;
    }

    public CheckType type() {
        return type;
    }

    public boolean enabled() {
        return plugin.config().isEnabled(type);
    }

    /** Players with this permission are never checked. */
    protected boolean bypass(Player player) {
        return player.hasPermission("wepac.bypass")
                || player.isDead()
                || player.isInsideVehicle()
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE;
    }

    /** Effective threshold for this check on this player (AI-adaptive + police record). */
    public int threshold(PlayerData data) {
        int base = plugin.config().threshold(type);
        double mul = plugin.ai().adaptiveMultiplier(data)
                * plugin.records().thresholdMultiplier(data.uuid);
        return Math.max(1, (int) Math.round(base * mul));
    }

    /** Register one violation with severity (>=1) and human-readable context. */
    public void flag(Player player, PlayerData data, String info, int severity) {
        plugin.violations().handle(player, data, type, info, severity, threshold(data));
    }

    public void flag(Player player, PlayerData data, String info) {
        flag(player, data, info, 1);
    }
}
