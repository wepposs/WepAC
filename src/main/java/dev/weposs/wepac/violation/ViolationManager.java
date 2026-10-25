package dev.weposs.wepac.violation;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.config.ConfigManager;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;

/** Routes violations: VL bookkeeping, alerts, punishment stages with retries. */
public final class ViolationManager {

    private final WepAC plugin;

    public ViolationManager(WepAC plugin) {
        this.plugin = plugin;
    }

    public void handle(Player player, PlayerData data, CheckType type, String info,
                       int severity, int threshold) {
        data.addVl(type, severity);
        int vl = data.vl(type);

        plugin.alerts().send(player, type, vl, info);

        if (!plugin.config().punishmentsEnabled()) return;
        if (vl < threshold) return;

        // highest stage whose 'at' is reached
        ConfigManager.PunishStage applicable = null;
        for (ConfigManager.PunishStage stage : plugin.config().stages(type)) {
            if (vl >= stage.at) applicable = stage;
        }
        if (applicable == null) return;

        String stageKey = type.name() + ":" + applicable.at;
        if (data.firedStages.contains(stageKey)) return;

        // PunishmentManager defers kick/ban until the verdict is confirmed;
        // deferred stages are retried on every further VL.
        if (plugin.punishments().tryExecute(player, type, vl, applicable.command)) {
            data.firedStages.add(stageKey);
        }
    }
}
