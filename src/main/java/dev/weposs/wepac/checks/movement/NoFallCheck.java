package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Server-side fall damage expectation is tracked in {@link MovementMonitor};
 * this listener clears the expectation when real fall damage arrives.
 */
public final class NoFallCheck extends Check {

    public NoFallCheck(WepAC plugin) {
        super(plugin, CheckType.NOFALL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        plugin.data().get(player).pendingFallDamage = false;
    }
}
