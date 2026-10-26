package dev.weposs.wepac.data;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.ai.AIBrain;
import dev.weposs.wepac.check.CheckType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-session persistence of violation levels.
 *
 * Without this a cheater sitting at VL 24/25 simply reconnects and starts from
 * zero - every rule-based step towards the kick is lost, and only records.yml
 * (already-fired strikes) survives. VL and fired stages are snapshotted on
 * quit, restored on join, and expire after {@code vl-persistence.expire-days}
 * (VL decay only runs while online, so stale violations must not live forever).
 *
 * Threading: the store map is concurrent and its values are immutable
 * snapshots; YAML is built on the main thread and written asynchronously
 * (synchronously at shutdown).
 */
public final class ViolationStore {

    private static final class Entry {
        final Map<String, Integer> vl;
        final Set<String> stages;
        final long savedAt;

        Entry(Map<String, Integer> vl, Set<String> stages, long savedAt) {
            this.vl = vl;
            this.stages = stages;
            this.savedAt = savedAt;
        }
    }

    private final WepAC plugin;
    private final Map<UUID, Entry> store = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public ViolationStore(WepAC plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), plugin.config().vlPersistFile);
    }

    public void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        long expireMs = plugin.config().vlExpireDays * 86_400_000L;
        long now = System.currentTimeMillis();
        int loaded = 0;
        int expired = 0;
        for (String key : y.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            long savedAt = y.getLong(key + ".time", 0L);
            if (savedAt == 0L || now - savedAt > expireMs) {
                expired++;
                continue;
            }
            Map<String, Integer> vl = new HashMap<>();
            ConfigurationSection vs = y.getConfigurationSection(key + ".vl");
            if (vs != null) {
                for (String k : vs.getKeys(false)) {
                    vl.put(k, vs.getInt(k));
                }
            }
            if (vl.isEmpty()) continue;
            Set<String> stages = new HashSet<>(y.getStringList(key + ".stages"));
            store.put(id, new Entry(vl, stages, savedAt));
            loaded++;
        }
        if (loaded > 0 || expired > 0) {
            plugin.getLogger().info("Loaded " + loaded + " persisted VL records ("
                    + expired + " expired and dropped).");
            dirty = expired > 0; // prune expired entries on the next flush
        }
    }

    /** Main thread, on quit. */
    public void snapshot(Player player, PlayerData data) {
        if (!plugin.config().vlPersistEnabled) return;
        UUID id = player.getUniqueId();
        Map<String, Integer> vl = new HashMap<>();
        for (Map.Entry<CheckType, Integer> e : data.violations.entrySet()) {
            if (e.getValue() > 0) vl.put(e.getKey().name(), e.getValue());
        }
        if (vl.isEmpty()) {
            if (store.remove(id) != null) dirty = true;
            return;
        }
        store.put(id, new Entry(vl, new HashSet<>(data.firedStages),
                System.currentTimeMillis()));
        dirty = true;
    }

    /** Main thread, on join. */
    public void restore(Player player, PlayerData data) {
        if (!plugin.config().vlPersistEnabled) return;
        Entry e = store.get(player.getUniqueId());
        if (e == null) return;
        for (Map.Entry<String, Integer> en : e.vl.entrySet()) {
            try {
                data.violations.merge(CheckType.valueOf(en.getKey()), en.getValue(), Integer::sum);
            } catch (IllegalArgumentException ignored) {
                // check renamed/removed between versions - drop its VL
            }
        }
        data.firedStages.addAll(e.stages);
        if (data.totalVl() > 0) {
            plugin.debug("Restored " + data.totalVl() + " VL for " + player.getName()
                    + " from a previous session");
        }
    }

    /** Staff rehabilitation (/ac resetvl) must clear the persisted copy too. */
    public void clear(UUID id) {
        if (store.remove(id) != null) dirty = true;
    }

    /** Main thread: snapshot the YAML, write it asynchronously. */
    public void saveNow() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = buildYaml();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeYaml(y));
    }

    /** Blocking write for shutdown, when async tasks may no longer run. */
    public void flushSync() {
        if (!dirty) return;
        dirty = false;
        writeYaml(buildYaml());
    }

    private YamlConfiguration buildYaml() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> e : store.entrySet()) {
            String key = e.getKey().toString();
            Entry en = e.getValue();
            y.set(key + ".time", en.savedAt);
            for (Map.Entry<String, Integer> v : en.vl.entrySet()) {
                y.set(key + ".vl." + v.getKey(), v.getValue());
            }
            y.set(key + ".stages", new ArrayList<>(en.stages));
        }
        return y;
    }

    private void writeYaml(YamlConfiguration y) {
        try {
            File f = file();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(parent, f.getName() + ".tmp");
            y.save(tmp);
            AIBrain.atomicReplace(tmp, f);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save violations: " + ex.getMessage());
        }
    }
}
