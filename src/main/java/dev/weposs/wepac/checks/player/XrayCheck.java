package dev.weposs.wepac.checks.player;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Statistical Xray. No server-side anti-cheat can see through the client, but
 * the ORE FIND RATE is a hard statistic: vanilla branch mining yields roughly
 * 0.5-1.5 diamond-tier ores per 1000 stone; an xray user walks straight to ores
 * and shows 10-100x the ratio.
 *
 * Deliberately conservative to protect honest players:
 *  - needs {min-stone} stone mined AND {min-valuables} valuables found,
 *  - ratio threshold far above any legitimate luck (silk/fortune irrelevant -
 *    we count ore BLOCKS broken, not drops),
 *  - long observation window per session, escalating alerts instead of instant
 *    punishment.
 */
public final class XrayCheck extends Check {

    private static final Set<Material> STONE = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.NETHERRACK, Material.NETHER_BRICKS,
            Material.BLACKSTONE, Material.BASALT, Material.TUFF, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.DRIPSTONE_BLOCK, Material.CALCITE);

    private static final Set<Material> VALUABLE = Set.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS);

    public XrayCheck(WepAC plugin) {
        super(plugin, CheckType.XRAY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        Material type = event.getBlock().getType();
        PlayerData data = plugin.data().get(player);

        if (STONE.contains(type)) {
            data.minedStone++;
        } else if (VALUABLE.contains(type)) {
            data.minedValuable++;
        } else {
            return;
        }

        long minStone = plugin.config().optLongOr(CheckType.XRAY, "min-stone", 500L);
        long minValuables = plugin.config().optLongOr(CheckType.XRAY, "min-valuables", 15L);
        double maxRatio = plugin.config().optDouble(CheckType.XRAY, "max-ratio-per-1000", 8.0);

        if (data.minedStone < minStone || data.minedValuable < minValuables) return;

        double ratio = data.minedValuable * 1000.0 / data.minedStone;
        if (ratio > maxRatio) {
            data.xrayFlagCount++;
            flag(player, data, String.format(Locale.ROOT,
                    "found %d diamond-tier ores in %d stone (%.1f per 1000, legit ~0.5-1.5) - strike %d",
                    data.minedValuable, data.minedStone, ratio, data.xrayFlagCount), 5);
            // reset the window so one mining session is not judged twice on the same ores
            data.minedStone = 0;
            data.minedValuable = 0;
        }
    }
}
