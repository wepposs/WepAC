package dev.weposs.wepac.record;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "police record" system.
 *
 *  CLEAN  -> first confirmed kick puts the player ON RECORD (WATCH):
 *            thresholds shrink, every flag is shown to staff, join is announced.
 *  WATCH  -> another strike triggers a FULL BEHAVIOUR REVIEW (dossier score
 *            from all active VLs, distinct checks, AI probability, history).
 *            score >= review-ban-score  -> WANTED + auto-ban + rage alert;
 *            below                      -> the anti-cheat complains VERY loudly.
 *  WANTED -> any further strike keeps screaming for a ban.
 *
 * Records persist in records.yml and survive restarts.
 */
public final class RecordManager {

    public enum Status { CLEAN, WATCH, WANTED }

    public static final class Record {
        public Status status = Status.CLEAN;
        public int kicks = 0;
        public int bans = 0;
        public long since = 0L;
        public final List<String> notes = new ArrayList<>();
    }

    // main-thread only (SimpleDateFormat is not thread-safe; all record
    // mutations happen on the main thread, the async flush writes a snapshot)
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM HH:mm");

    private final WepAC plugin;
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public RecordManager(WepAC plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private File file() {
        return new File(plugin.getDataFolder(), plugin.config().recordFile);
    }

    public void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String key : y.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Record r = new Record();
            r.status = parseStatus(y.getString(key + ".status", "CLEAN"));
            r.kicks = y.getInt(key + ".kicks", 0);
            r.bans = y.getInt(key + ".bans", 0);
            r.since = y.getLong(key + ".since", 0L);
            r.notes.addAll(y.getStringList(key + ".notes"));
            records.put(id, r);
        }
        plugin.getLogger().info("Loaded " + records.size() + " criminal records.");
    }

    /**
     * Marks the store dirty; the actual disk write is batched by the periodic
     * flush task. Strikes must never block the main thread on YAML IO.
     */
    public void save() {
        dirty = true;
    }

    /** Main thread: snapshot the YAML, write it on an async thread. */
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
        for (Map.Entry<UUID, Record> e : records.entrySet()) {
            String key = e.getKey().toString();
            Record r = e.getValue();
            if (r.status == Status.CLEAN && r.kicks == 0 && r.bans == 0 && r.notes.isEmpty()) {
                continue; // don't store clean players
            }
            y.set(key + ".status", r.status.name());
            y.set(key + ".kicks", r.kicks);
            y.set(key + ".bans", r.bans);
            y.set(key + ".since", r.since);
            synchronized (r.notes) {
                y.set(key + ".notes", new ArrayList<>(r.notes));
            }
        }
        return y;
    }

    private void writeYaml(YamlConfiguration y) {
        try {
            File f = file();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // temp+move: a crash mid-write must not corrupt the record base
            File tmp = new File(parent, f.getName() + ".tmp");
            y.save(tmp);
            dev.weposs.wepac.ai.AIBrain.atomicReplace(tmp, f);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save records: " + ex.getMessage());
        }
    }

    private static Status parseStatus(String s) {
        try {
            return Status.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Status.CLEAN;
        }
    }

    // ------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------

    public Record get(UUID id) {
        return records.computeIfAbsent(id, k -> new Record());
    }

    /**
     * Read-only status. Must NOT create entries: this runs on every alert
     * (statusTag) and threshold query, and computeIfAbsent here grew the map
     * with one Record per player ever seen.
     */
    public Status status(UUID id) {
        Record r = records.get(id);
        return r == null ? Status.CLEAN : r.status;
    }

    /** Threshold multiplier for watched/wanted players (stricter checks). */
    public double thresholdMultiplier(UUID id) {
        if (!plugin.config().recordEnabled) return 1.0;
        return switch (status(id)) {
            case WATCH -> plugin.config().watchMultiplier;
            case WANTED -> plugin.config().wantedMultiplier;
            default -> 1.0;
        };
    }

    // ------------------------------------------------------------------
    // Strikes
    // ------------------------------------------------------------------

    /** Called by PunishmentManager after a confirmed kick/ban executed. */
    public void addStrike(Player player, CheckType type, boolean isBan) {
        if (!plugin.config().recordEnabled) return;
        // anti-poisoning: wipe any "legit PvP" samples this player ever donated
        plugin.ai().purgeSamplesFrom(player.getUniqueId());
        Record r = get(player.getUniqueId());
        String note = DATE.format(new Date()) + " " + type.displayName() + (isBan ? " BAN" : " KICK");
        if (isBan) r.bans++;
        else r.kicks++;
        r.notes.add(note);
        while (r.notes.size() > 50) r.notes.remove(0);

        if (isBan) {
            r.status = Status.WANTED;
            r.since = System.currentTimeMillis();
            plugin.alerts().broadcastRaw("&4&l[РОЗЫСК]&r &c" + player.getName()
                    + " &7забанен (&c" + type.displayName() + "&7) — внесён в базу как рецидивист.");
        } else if (r.status == Status.CLEAN) {
            r.status = Status.WATCH;
            r.since = System.currentTimeMillis();
            plugin.alerts().broadcastRaw("&6&l[НА УЧЁТ]&r &7" + player.getName()
                    + " &7кикнут впервые (&c" + type.displayName()
                    + "&7). Поставлен на учёт: пристальное наблюдение, пороги ужесточены.");
        } else if (r.status == Status.WATCH) {
            review(player, r, type);
        } else {
            plugin.alerts().rage(player, "рецидив в статусе В РОЗЫСКЕ (" + type.displayName() + ")");
        }
        save();
    }

    /** Full behaviour review: builds the dossier score and decides ban vs complain. */
    private void review(Player player, Record r, CheckType type) {
        double score = reviewScore(player);
        PlayerData d = plugin.data().get(player);
        long distinct = d.violations.values().stream().filter(v -> v > 0).count();

        plugin.alerts().broadcastRaw(String.format(Locale.ROOT,
                "&4&l[ПРОВЕРКА ПОВЕДЕНИЯ]&r &7%s &7пойман повторно (&c%s&7). Досье: "
                        + "&fscore %.0f&7, активных проверок &f%d&7, суммарный VL &f%d&7, ИИ &f%.0f%%&7, страйков &f%d",
                player.getName(), type.displayName(), score, distinct,
                d.totalVl(), d.aiProbability * 100, r.kicks + r.bans));

        r.notes.add(DATE.format(new Date()) + String.format(Locale.ROOT,
                " REVIEW score=%.0f (%s)", score, type.displayName()));

        if (score >= plugin.config().reviewBanScore) {
            r.status = Status.WANTED;
            String cmd = plugin.config().recordBanCommand
                    .replace("%player%", player.getName())
                    .replace("%check%", type.displayName())
                    .replace("%score%", String.format(Locale.ROOT, "%.0f", score));
            plugin.ai().labelFromPunishment(player);
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                if (!ok) {
                    plugin.getLogger().warning("Record-ban command was NOT executed "
                            + "(is a punishment plugin installed?): " + cmd);
                }
            });
            plugin.getLogger().info("RECORD-BAN " + player.getName() + " score=" + score);
            plugin.alerts().rage(player, "повторные читы после учёта, score " + String.format(Locale.ROOT, "%.0f", score));
        } else {
            // not conclusive enough for an auto-ban -> complain VERY loudly
            plugin.alerts().rage(player, "подозрителен, но для автобана не хватило score ("
                    + String.format(Locale.ROOT, "%.0f", score) + "/" + String.format(Locale.ROOT, "%.0f", plugin.config().reviewBanScore) + ")");
        }
    }

    /** Dossier score: everything we know about this player, in one number. */
    public double reviewScore(Player player) {
        PlayerData d = plugin.data().get(player);
        long distinct = d.violations.values().stream().filter(v -> v > 0).count();
        Record r = get(player.getUniqueId());
        return distinct * 20.0
                + Math.min(d.totalVl(), 100) * 0.5
                + d.aiProbability * 40.0
                + (r.kicks + r.bans) * 15.0;
    }

    // ------------------------------------------------------------------
    // Manual control
    // ------------------------------------------------------------------

    public void setStatus(Player player, Status status, String byWhom) {
        Record r = get(player.getUniqueId());
        r.status = status;
        if (status != Status.CLEAN && r.since == 0L) r.since = System.currentTimeMillis();
        r.notes.add(DATE.format(new Date()) + " status -> " + status + " by " + byWhom);
        save();
        plugin.alerts().broadcastRaw("&7Статус &f" + player.getName() + "&7: &6"
                + statusLabel(status) + " &8(" + byWhom + ")");
    }

    public void clear(UUID id) {
        records.remove(id);
        save();
    }

    public static String statusLabel(Status s) {
        return switch (s) {
            case CLEAN -> "&aчист";
            case WATCH -> "&6НА УЧЁТЕ";
            case WANTED -> "&c&lВ РОЗЫСКЕ";
        };
    }

    /**
     * D2: WATCH must not be a life sentence - one false-positive kick used to
     * mean 0.6x thresholds and join announcements forever. After expire-days
     * without a strike the watch lapses back to CLEAN (notes are preserved;
     * WANTED stays until manually cleared - that is a deliberate ban record).
     */
    private void expireIfStale(UUID id) {
        Record r = records.get(id);
        if (r == null || r.status != Status.WATCH) return;
        int days = plugin.config().recordExpireDays;
        if (days <= 0 || r.since <= 0L) return;
        if (System.currentTimeMillis() - r.since > days * 86_400_000L) {
            r.status = Status.CLEAN;
            r.notes.add(DATE.format(new Date()) + " WATCH auto-expired ("
                    + days + "d without strikes)");
            save();
        }
    }

    /** Join announcement for staff. */
    public void announceJoin(Player player) {
        if (!plugin.config().recordEnabled) return;
        expireIfStale(player.getUniqueId());
        Status s = status(player.getUniqueId());
        if (s == Status.WATCH) {
            plugin.alerts().broadcastRaw("&6&l[УЧЁТ]&r &7" + player.getName()
                    + " &7вошёл на сервер. Он &6на учёте&7 — пристальное наблюдение активно.");
        } else if (s == Status.WANTED) {
            plugin.alerts().broadcastRaw("&4&l[РОЗЫСК]&r &c" + player.getName()
                    + " &7вошёл на сервер. Он &c&lВ РОЗЫСКЕ&7 — при любом нарушении банить немедленно.");
        }
    }
}
