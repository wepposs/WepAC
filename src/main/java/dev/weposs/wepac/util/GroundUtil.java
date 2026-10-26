package dev.weposs.wepac.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Set;

public final class GroundUtil {

    private static final Set<Material> CLIMBABLE = Set.of(
            Material.LADDER, Material.VINE, Material.SCAFFOLDING,
            Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
            Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT);

    private GroundUtil() {
    }

    /**
     * Server-authoritative ground check based on block bounding boxes AND
     * standable entities. Immune to client "on ground" packet spoofing.
     */
    public static boolean isOnGround(Player player) {
        return onBlockGround(player) || onEntityGround(player);
    }

    private static boolean onBlockGround(Player player) {
        BoundingBox box = player.getBoundingBox();
        double feet = box.getMinY();
        int y = (int) Math.floor(feet - 0.08);
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Block block = player.getWorld().getBlockAt(x, y, z);
                // A block supports the player if it has a collision box (not passable).
                // This covers snow layers, carpets, slabs, stairs, scaffolding - not
                // just Material#isSolid() (snow layer / carpet report isSolid()=false,
                // which previously made players standing on them count as airborne).
                if (block.isPassable()) continue;
                BoundingBox bb = block.getBoundingBox();
                // feet rest on (or slightly sink into) the block top
                if (feet <= bb.getMaxY() + 0.08 && feet >= bb.getMaxY() - 0.35) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Vanilla lets players STAND ON ENTITIES: other players (lobby piles), mobs,
     * boats, shulkers, armor stands. Without this, 0.8s on somebody's head was
     * "hover" -> Fly flag + GroundSpoof streak -> confirm-gate -> autokick,
     * the single most mass-scale false positive possible on a busy server.
     */
    private static boolean onEntityGround(Player player) {
        BoundingBox box = player.getBoundingBox();
        double feet = box.getMinY();
        for (org.bukkit.entity.Entity e : player.getWorld()
                .getNearbyEntities(box.expand(0.15, 0.4, 0.15))) {
            if (e.equals(player)) continue;
            if (!(e instanceof org.bukkit.entity.LivingEntity)
                    && !(e instanceof org.bukkit.entity.Boat)) {
                continue;
            }
            if (e instanceof org.bukkit.entity.ArmorStand stand && stand.isMarker()) {
                continue; // markers have no hitbox - you fall through them
            }
            BoundingBox eb = e.getBoundingBox();
            // the entity's top must sit right at (or slightly under) the feet
            if (eb.getMaxY() < feet - 0.35 || eb.getMaxY() > feet + 0.15) continue;
            // ...and overlap the player horizontally
            if (eb.getMaxX() < box.getMinX() || eb.getMinX() > box.getMaxX()) continue;
            if (eb.getMaxZ() < box.getMinZ() || eb.getMinZ() > box.getMaxZ()) continue;
            return true;
        }
        return false;
    }

    public static boolean onClimbable(Player player) {
        Block atFeet = player.getLocation().getBlock();
        if (CLIMBABLE.contains(atFeet.getType())) return true;
        Block atEyes = player.getEyeLocation().getBlock();
        return CLIMBABLE.contains(atEyes.getType());
    }

    public static boolean inLiquid(Player player) {
        Block feet = player.getLocation().getBlock();
        if (feet.isLiquid() || feet.getType() == Material.BUBBLE_COLUMN) return true;
        Block below = feet.getRelative(0, -1, 0);
        return below.isLiquid() || below.getType() == Material.BUBBLE_COLUMN;
    }

    /** True when the player stands on a liquid surface without sinking (Jesus/lava-walk). */
    public static boolean onLiquidSurface(Player player) {
        BoundingBox box = player.getBoundingBox();
        Block feetBlock = player.getLocation().getBlock();
        Block below = feetBlock.getRelative(0, -1, 0);
        Material belowType = below.getType();
        if (belowType != Material.WATER && belowType != Material.LAVA) return false;
        if (feetBlock.getType() == belowType && player.isSwimming()) return false;
        if (belowType == Material.WATER
                && below.getRelative(0, 1, 0).getType() == Material.LILY_PAD) return false;
        // feet must sit right at the surface plane of the liquid block below
        double surface = below.getY() + 1.0;
        return Math.abs(box.getMinY() - surface) < 0.25;
    }

    public static boolean onSlimeOrIce(Player player) {
        Block below = player.getLocation().getBlock().getRelative(0, -1, 0);
        Material m = below.getType();
        return m == Material.SLIME_BLOCK || m.name().contains("ICE");
    }

    public static boolean softLanding(Player player) {
        Block below = player.getLocation().getBlock().getRelative(0, -1, 0);
        Material m = below.getType();
        return m == Material.WATER || m == Material.SLIME_BLOCK
                || m == Material.POWDER_SNOW || m == Material.HAY_BLOCK
                || m.name().endsWith("_BED") || m.name().endsWith("WOOL");
    }

    /**
     * Returns the solid wall block the player is currently pressed against
     * (within 0.12 blocks of the body), or null if there is no wall.
     * Used by the WallClimb/Spider detection: vanilla players cannot move UP
     * along a wall without ladders/vines/scaffolding.
     */
    public static Block adjacentWall(Player player) {
        BoundingBox box = player.getBoundingBox();
        double expand = 0.12;
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY() - 0.01);
        int minX = (int) Math.floor(box.getMinX() - expand);
        int maxX = (int) Math.floor(box.getMaxX() + expand);
        int minZ = (int) Math.floor(box.getMinZ() - expand);
        int maxZ = (int) Math.floor(box.getMaxZ() + expand);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    Material m = block.getType();
                    if (!m.isSolid() || CLIMBABLE.contains(m)) continue;
                    BoundingBox bb = block.getBoundingBox();
                    // must touch the player horizontally and overlap vertically
                    boolean touchesX = bb.getMaxX() >= box.getMinX() - expand
                            && bb.getMinX() <= box.getMaxX() + expand
                            && (bb.getMinX() >= box.getMaxX() - expand || bb.getMaxX() <= box.getMinX() + expand);
                    boolean touchesZ = bb.getMaxZ() >= box.getMinZ() - expand
                            && bb.getMinZ() <= box.getMaxZ() + expand
                            && (bb.getMinZ() >= box.getMaxZ() - expand || bb.getMaxZ() <= box.getMinZ() + expand);
                    boolean overlapsY = bb.getMaxY() > box.getMinY() + 0.2
                            && bb.getMinY() < box.getMaxY();
                    if ((touchesX || touchesZ) && overlapsY) {
                        return block;
                    }
                }
            }
        }
        return null;
    }
}
