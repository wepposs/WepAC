package dev.weposs.wepac.checks.block;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Locale;

/**
 * FastEat: consuming food/potions faster than vanilla (~1600ms).
 * Dried kelp is excluded - it legitimately eats in ~400ms.
 * Violating consume events are cancelled so the cheat grants no benefit.
 */
public final class FastEatCheck extends Check {

    public FastEatCheck(WepAC plugin) {
        super(plugin, CheckType.FASTEAT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsumeStart(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        Material item = player.getInventory().getItemInMainHand().getType();
        if (!item.isEdible() || item == Material.DRIED_KELP) return;

        PlayerData data = plugin.data().get(player);
        long now = System.currentTimeMillis();
        // keep the earliest pending start; ignore stale ones
        if (data.consumeStart == 0L || now - data.consumeStart > 2500L) {
            data.consumeStart = now;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;
        if (event.getItem().getType() == Material.DRIED_KELP) return;

        PlayerData data = plugin.data().get(player);
        long start = data.consumeStart;
        data.consumeStart = 0L;
        if (start == 0L) return;

        long took = System.currentTimeMillis() - start;
        long minMs = plugin.config().optLongOr(CheckType.FASTEAT, "min-consume-ms", 900L);
        if (took < minMs) {
            event.setCancelled(true);
            flag(player, data, String.format(Locale.ROOT,
                    "consumed %s in %dms (min %dms)",
                    event.getItem().getType().name(), took, minMs), 2);
        }
    }
}
