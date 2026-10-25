package dev.weposs.wepac.checks.block;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.Locale;

/**
 * Natural saturation regen fires at most every ~4s in vanilla.
 * Faster regen events indicate a regen hack; violating events are cancelled.
 */
public final class FastRegenCheck extends Check {

    public FastRegenCheck(WepAC plugin) {
        super(plugin, CheckType.FASTREGEN);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!enabled() || bypass(player)) return;

        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason != EntityRegainHealthEvent.RegainReason.SATIATED
                && reason != EntityRegainHealthEvent.RegainReason.REGEN) {
            return;
        }

        PlayerData data = plugin.data().get(player);
        long now = System.currentTimeMillis();
        long minInterval = plugin.config().optLongOr(CheckType.FASTREGEN, "min-interval-ms", 3000L);
        long last = data.lastRegenTime;
        data.lastRegenTime = now;
        if (last == 0L) return;

        long interval = now - last;
        if (interval < minInterval) {
            event.setCancelled(true);
            flag(player, data, String.format(Locale.ROOT,
                    "regen after %dms (min %dms)", interval, minInterval), 2);
        }
    }
}
