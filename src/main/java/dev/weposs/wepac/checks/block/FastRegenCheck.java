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
 * Natural regen comes in two vanilla branches, both arriving as SATIATED:
 * food=20 with saturation regenerates 1 HP every 500ms, the unsaturated
 * branch (food>=18) only every ~4s. Judging both against one 3s floor used
 * to CANCEL and punish the legitimate regen of every well-fed player.
 * The saturated/peaceful branch is judged against a 400ms floor instead -
 * regen hacks run at 50-150ms and are still caught.
 * Violating events are cancelled so the cheat grants no benefit.
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
        boolean fastBranch = reason == EntityRegainHealthEvent.RegainReason.REGEN
                || (player.getFoodLevel() >= 20 && player.getSaturation() > 0f);
        long minInterval = fastBranch
                ? plugin.config().optLongOr(CheckType.FASTREGEN, "min-interval-saturated-ms", 400L)
                : plugin.config().optLongOr(CheckType.FASTREGEN, "min-interval-ms", 3000L);
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
