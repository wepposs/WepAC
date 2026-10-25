package dev.weposs.wepac.checks.combat;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Locale;

/**
 * FastBow: a fully-forced arrow released faster than physically possible.
 * Vanilla needs ~1100ms to charge a bow; crossbows are excluded entirely
 * (Quick Charge enchantment is legitimate).
 */
public final class FastBowCheck extends Check {

    public FastBowCheck(WepAC plugin) {
        super(plugin, CheckType.FASTBOW);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDraw(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;
        if (player.getInventory().getItemInMainHand().getType() != Material.BOW) return;

        PlayerData data = plugin.data().get(player);
        // each right-click with a bow is a fresh draw; record its start time.
        // (The old 3s guard ignored re-draws, so a second quick shot measured
        // charge from the FIRST draw and escaped detection.)
        data.bowDrawTime = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!enabled() || bypass(player)) return;

        PlayerData data = plugin.data().get(player);
        long draw = data.bowDrawTime;
        data.bowDrawTime = 0L;
        if (draw == 0L) return;

        long chargeMs = System.currentTimeMillis() - draw;
        long minCharge = plugin.config().optLongOr(CheckType.FASTBOW, "min-charge-ms", 300L);
        double minForce = plugin.config().optDouble(CheckType.FASTBOW, "min-force", 0.75);

        if (chargeMs < minCharge && event.getForce() >= minForce) {
            flag(player, data, String.format(Locale.ROOT,
                    "bow charged in %dms with force %.2f (vanilla needs ~1100ms)",
                    chargeMs, event.getForce()), 2);
        }
    }
}
