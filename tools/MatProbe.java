import org.bukkit.Material;
public class MatProbe {
    public static void main(String[] a) {
        Material[] ms = {Material.SNOW, Material.SNOW_BLOCK, Material.WHITE_CARPET,
                Material.LILY_PAD, Material.SCAFFOLDING, Material.STONE_SLAB,
                Material.MOSS_CARPET, Material.RAIL, Material.TRIPWIRE, Material.POWDER_SNOW};
        for (Material m : ms) System.out.println(m + " isSolid=" + m.isSolid() + " isOccluding=" + m.isOccluding());
    }
}
