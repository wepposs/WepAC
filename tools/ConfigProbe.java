import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Offline verification of the FIXED punishment-stage parsing. */
public class ConfigProbe {
    public static void main(String[] args) throws Exception {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml"));

        System.out.println("=== punishments.default-stages (FIXED: getMapList from root) ===");
        List<Map<?, ?>> def = c.getMapList("punishments.default-stages");
        System.out.println("size = " + def.size());
        for (Map<?, ?> m : def) System.out.println("  at=" + m.get("at") + " cmd='" + m.get("command") + "'");

        System.out.println("=== punishments.overrides.BOATFLY (FIXED) ===");
        ConfigurationSection ov = c.getConfigurationSection("punishments.overrides");
        if (ov != null) {
            for (String key : ov.getKeys(false)) {
                List<Map<?, ?>> list = c.getMapList("punishments.overrides." + key);
                System.out.println("  override " + key + " size=" + list.size());
                for (Map<?, ?> m : list) System.out.println("    at=" + m.get("at") + " cmd='" + m.get("command") + "'");
            }
        } else {
            System.out.println("  overrides section NULL");
        }

        System.out.println("=== configKey() check (GROUND_SPOOF must map to existing section) ===");
        String key = "GROUND_SPOOF".toLowerCase().replace("_", "");
        System.out.println("  derived key = '" + key + "', section present = "
                + (c.getConfigurationSection("checks." + key) != null)
                + ", enabled = " + c.getBoolean("checks." + key + ".enabled", true)
                + ", threshold = " + c.getInt("checks." + key + ".threshold", 20));

        System.out.println("=== killaura new options present ===");
        for (String k : new String[]{"gcd-yaw", "gui-attacks", "cooldown-lock", "buffer-reset-gap-ms", "perfect-hits"}) {
            System.out.println("  killaura." + k + " = " + c.get("checks.killaura." + k));
        }
        System.out.println("=== silentrot tuned ===");
        System.out.println("  min-reversals=" + c.getDouble("checks.silentrot.min-reversals")
                + " min-swing-deg=" + c.getDouble("checks.silentrot.min-swing-deg"));
    }
}
