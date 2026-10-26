package dev.weposs.wepac.config;

import dev.weposs.wepac.check.CheckType;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Typed view over config.yml with reload support. */
public final class ConfigManager {

    public static final class PunishStage {
        public final int at;
        public final String command;

        public PunishStage(int at, String command) {
            this.at = at;
            this.command = command;
        }
    }

    private final JavaPlugin plugin;

    private String prefix;
    private String alertFormat;
    private boolean alertConsole;
    public long alertCooldownMs;

    // cross-session VL persistence
    public boolean vlPersistEnabled;
    public String vlPersistFile;
    public int vlExpireDays;

    private int decayInterval;
    private int decayAmount;

    // false-positive guards
    public boolean lagGuard;
    public int lagPingThreshold;
    public boolean rollbackEnabled;

    // kick/ban confirmation
    public boolean confirmEnabled;
    public int confirmMinDistinctChecks;
    public double confirmSingleVlMultiplier;
    public double confirmAiProbability;
    /** punishment verbs that require confirmation; UNKNOWN verbs count as harsh
     *  (conservative), only safeVerbs are exempt */
    public List<String> safeVerbs = new ArrayList<>();

    // police record ("учёт")
    public boolean recordEnabled;
    public String recordFile;
    /** WATCH auto-downgrades to CLEAN after this many days without strikes (0 = never) */
    public int recordExpireDays;
    public double watchMultiplier;
    public double wantedMultiplier;
    public double reviewBanScore;
    public String recordBanCommand;
    public String rageMessage;
    public int rageRepeat;

    // client detection
    public boolean clientDetectEnabled;
    public boolean clientNotifyStaff;
    public boolean clientWatchUnknown;
    public boolean clientBanOnBrand;
    public String clientBanCommand;
    public List<String> cheatBrands = new ArrayList<>();
    public List<String> watchBrands = new ArrayList<>();
    public Map<String, String> knownBrands = new HashMap<>();

    // packet layer
    public boolean packetEnabled;
    public double timerMaxRate;
    public long timerSustainMs;
    public long blinkTimeoutMs;

    private boolean punishmentsEnabled;
    private List<PunishStage> defaultStages;
    private Map<CheckType, List<PunishStage>> stageOverrides;

    // AI
    public boolean aiEnabled;
    public double aiLearningRate;
    public int aiBatchSize;
    public int aiTrainInterval;
    public int aiScoreInterval;
    public int aiSaveIntervalMinutes;
    public String aiModelFile;
    public boolean aiLabelFromPunishments;
    public boolean aiLabelFromCleanPlay;
    public int aiCleanPlayMinutes;
    public int aiMaxCleanSamples;
    public double aiAlertThreshold;
    public boolean aiAdaptiveThresholds;
    public double aiAdaptiveMin;
    public double aiAdaptiveMax;
    public boolean aiPunishByAi;
    public double aiPunishProbability;

    // learning from real PvP ("how honest players fight")
    public boolean pvpLearningEnabled;
    public int pvpSessionTimeoutSeconds;
    public int pvpMinSessionSeconds;
    public int pvpMinHits;
    public double pvpMaxAiProbability;
    public int pvpMaxSamplesPerHour;

    // Checks
    private final Map<CheckType, Boolean> enabled = new HashMap<>();
    private final Map<CheckType, Integer> thresholds = new HashMap<>();
    private final Map<String, Object> options = new HashMap<>();
    /** per-world check disables (D3): minigame/parkour worlds need Fly/Speed/Jesus
     *  off for EVERYONE, not per-player bypass permissions */
    private final Map<CheckType, java.util.Set<String>> worldDisabled = new HashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        prefix = color(c.getString("prefix", "&8[&cWepAC&8]&r "));
        alertFormat = color(c.getString("alerts.format",
                "{prefix}&7{player} &8| &c{check} &8| &7VL {vl} &8| &7ping {ping}ms &8| &7AI {ai} &8| &f{info}"));
        alertConsole = c.getBoolean("alerts.console", true);
        alertCooldownMs = Math.max(0L, c.getLong("alerts.cooldown-ms", 1000L));

        vlPersistEnabled = c.getBoolean("vl-persistence.enabled", true);
        vlPersistFile = c.getString("vl-persistence.file", "vl.yml");
        vlExpireDays = Math.max(1, c.getInt("vl-persistence.expire-days", 7));

        decayInterval = Math.max(5, c.getInt("decay.interval", 30));
        decayAmount = Math.max(1, c.getInt("decay.amount", 1));

        lagGuard = c.getBoolean("guards.lag-guard", true);
        lagPingThreshold = c.getInt("guards.lag-ping-threshold", 450);
        rollbackEnabled = c.getBoolean("guards.rollback", true);

        confirmEnabled = c.getBoolean("confirm.enabled", true);
        confirmMinDistinctChecks = Math.max(1, c.getInt("confirm.min-distinct-checks", 2));
        confirmSingleVlMultiplier = c.getDouble("confirm.single-check-vl-multiplier", 1.5);
        confirmAiProbability = c.getDouble("confirm.ai-probability", 0.97);
        safeVerbs = new ArrayList<>();
        for (String s : c.getStringList("confirm.safe-commands")) {
            safeVerbs.add(s.toLowerCase(Locale.ROOT).trim());
        }
        if (safeVerbs.isEmpty()) {
            // sensible non-punishing defaults; ANY verb outside this list is
            // treated as harsh (D1: an unknown "vban"/"sanction" alias must not
            // slip past the confirmation gate)
            safeVerbs.addAll(List.of("broadcast", "say", "warn", "msg", "tell", "title", "me"));
        }

        recordEnabled = c.getBoolean("record.enabled", true);
        recordFile = c.getString("record.file", "records.yml");
        recordExpireDays = Math.max(0, c.getInt("record.expire-days", 30));
        watchMultiplier = c.getDouble("record.watch-threshold-multiplier", 0.6);
        wantedMultiplier = c.getDouble("record.wanted-threshold-multiplier", 0.4);
        reviewBanScore = c.getDouble("record.review-ban-score", 100);
        recordBanCommand = c.getString("record.ban-command",
                "tempban %player% 7d &c[WepAC] Рецидив после учёта (%check%)");
        rageMessage = c.getString("record.rage-message",
                "&4&l%player% &c&l— В РОЗЫСКЕ: подтверждённый читер. Баньте немедленно, вердикт окончательный.");
        rageRepeat = Math.max(1, c.getInt("record.rage-repeat", 3));

        clientDetectEnabled = c.getBoolean("client-detect.enabled", true);
        clientNotifyStaff = c.getBoolean("client-detect.notify-staff", true);
        clientWatchUnknown = c.getBoolean("client-detect.watch-unknown", true);
        // brands are self-reported; default is watch-not-ban so an unlucky
        // modpack brand can never cause an automated false ban
        clientBanOnBrand = c.getBoolean("client-detect.ban-on-brand", false);
        clientBanCommand = c.getString("client-detect.ban-command",
                "ban %player% &c[WepAC] Читерский клиент: %client%");
        cheatBrands = new ArrayList<>();
        for (String s : c.getStringList("client-detect.cheat-brands")) {
            cheatBrands.add(s.toLowerCase(Locale.ROOT).trim());
        }
        watchBrands = new ArrayList<>();
        for (String s : c.getStringList("client-detect.watch-brands")) {
            watchBrands.add(s.toLowerCase(Locale.ROOT).trim());
        }
        knownBrands = new HashMap<>();
        ConfigurationSection known = c.getConfigurationSection("client-detect.known-brands");
        if (known != null) {
            for (String key : known.getKeys(false)) {
                knownBrands.put(key.toLowerCase(Locale.ROOT).trim(), known.getString(key, key));
            }
        }

        packetEnabled = c.getBoolean("packet-sniffer.enabled", true);
        // 20.5 (not 21): vanilla is exactly 20/s - at an integer cap of 21 a
        // 1.05x timer (21.0 packets/s) sailed through the > comparison
        timerMaxRate = c.getDouble("packet-sniffer.timer-max-rate", 20.5);
        timerSustainMs = c.getLong("packet-sniffer.timer-sustain-ms", 2500L);
        blinkTimeoutMs = c.getLong("packet-sniffer.blink-timeout-ms", 2000L);

        punishmentsEnabled = c.getBoolean("punishments.enabled", true);
        defaultStages = readStages(c.getMapList("punishments.default-stages"));
        stageOverrides = new HashMap<>();
        ConfigurationSection overrides = c.getConfigurationSection("punishments.overrides");
        if (overrides != null) {
            for (String key : overrides.getKeys(false)) {
                try {
                    CheckType type = CheckType.valueOf(key.toUpperCase(Locale.ROOT));
                    stageOverrides.put(type,
                            readStages(c.getMapList("punishments.overrides." + key)));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Unknown check in punishments.overrides: " + key);
                }
            }
        }

        ConfigurationSection ai = c.getConfigurationSection("ai");
        aiEnabled = c.getBoolean("ai.enabled", true);
        aiLearningRate = ai != null ? ai.getDouble("learning-rate", 0.02) : 0.02;
        aiBatchSize = ai != null ? ai.getInt("batch-size", 32) : 32;
        aiTrainInterval = ai != null ? Math.max(10, ai.getInt("train-interval", 60)) : 60;
        aiScoreInterval = ai != null ? Math.max(1, ai.getInt("score-interval", 3)) : 3;
        aiSaveIntervalMinutes = ai != null ? Math.max(1, ai.getInt("save-interval-minutes", 5)) : 5;
        aiModelFile = ai != null ? ai.getString("model-file", "ai/model.bin") : "ai/model.bin";
        aiLabelFromPunishments = c.getBoolean("ai.auto-label.from-punishments", true);
        aiLabelFromCleanPlay = c.getBoolean("ai.auto-label.from-clean-play", true);
        aiCleanPlayMinutes = Math.max(1, c.getInt("ai.auto-label.clean-play-minutes", 20));
        aiMaxCleanSamples = Math.max(1, c.getInt("ai.auto-label.max-clean-samples", 3));
        aiAlertThreshold = c.getDouble("ai.alert-threshold", 0.90);
        aiAdaptiveThresholds = c.getBoolean("ai.adaptive-thresholds", true);
        aiAdaptiveMin = c.getDouble("ai.adaptive-min-multiplier", 0.65);
        aiAdaptiveMax = c.getDouble("ai.adaptive-max-multiplier", 1.15);
        aiPunishByAi = c.getBoolean("ai.punish-by-ai", false);
        aiPunishProbability = c.getDouble("ai.punish-probability", 0.995);

        pvpLearningEnabled = c.getBoolean("ai.pvp-learning.enabled", true);
        pvpSessionTimeoutSeconds = Math.max(3, c.getInt("ai.pvp-learning.session-timeout-seconds", 10));
        pvpMinSessionSeconds = Math.max(5, c.getInt("ai.pvp-learning.min-session-seconds", 15));
        pvpMinHits = Math.max(4, c.getInt("ai.pvp-learning.min-hits", 20));
        pvpMaxAiProbability = c.getDouble("ai.pvp-learning.max-ai-probability", 0.35);
        pvpMaxSamplesPerHour = Math.max(1, c.getInt("ai.pvp-learning.max-samples-per-hour", 3));

        enabled.clear();
        thresholds.clear();
        options.clear();
        worldDisabled.clear();
        ConfigurationSection checks = c.getConfigurationSection("checks");
        if (checks != null) {
            for (CheckType type : CheckType.values()) {
                String key = type.configKey();
                enabled.put(type, checks.getBoolean(key + ".enabled", true));
                thresholds.put(type, Math.max(1, checks.getInt(key + ".threshold", 20)));
                List<String> worlds = checks.getStringList(key + ".disabled-worlds");
                if (worlds != null && !worlds.isEmpty()) {
                    java.util.Set<String> set = new java.util.HashSet<>();
                    for (String w : worlds) set.add(w.toLowerCase(Locale.ROOT).trim());
                    worldDisabled.put(type, set);
                }
                ConfigurationSection sec = checks.getConfigurationSection(key);
                if (sec != null) {
                    for (String opt : sec.getKeys(false)) {
                        if (!opt.equals("enabled") && !opt.equals("threshold")) {
                            options.put(key + "." + opt, sec.get(opt));
                        }
                    }
                }
            }
        } else {
            for (CheckType type : CheckType.values()) {
                enabled.put(type, true);
                thresholds.put(type, 20);
            }
        }
    }

    private List<PunishStage> readStages(List<Map<?, ?>> list) {
        List<PunishStage> stages = new ArrayList<>();
        if (list == null) return stages;
        for (Map<?, ?> map : list) {
            Object cmd = map.get("command");
            stages.add(new PunishStage(
                    toInt(map.get("at"), Integer.MAX_VALUE),
                    cmd == null ? "" : String.valueOf(cmd)));
        }
        stages.sort((a, b) -> Integer.compare(a.at, b.at));
        return stages;
    }

    private static int toInt(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    public boolean isEnabled(CheckType type) {
        return enabled.getOrDefault(type, true);
    }

    /** True when this check is switched off for the given world (disabled-worlds). */
    public boolean worldDisabled(CheckType type, String world) {
        java.util.Set<String> set = worldDisabled.get(type);
        return set != null && world != null && set.contains(world.toLowerCase(Locale.ROOT));
    }

    public int threshold(CheckType type) {
        return thresholds.getOrDefault(type, 20);
    }

    public List<PunishStage> stages(CheckType type) {
        return stageOverrides.getOrDefault(type, defaultStages);
    }

    public String prefix() {
        return prefix;
    }

    public String alertFormat() {
        return alertFormat;
    }

    public boolean alertConsole() {
        return alertConsole;
    }

    public int decayInterval() {
        return decayInterval;
    }

    public int decayAmount() {
        return decayAmount;
    }

    public boolean punishmentsEnabled() {
        return punishmentsEnabled;
    }

    public double optDouble(CheckType type, String key, double def) {
        Object v = options.get(type.configKey() + "." + key);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public int optInt(CheckType type, String key, int def) {
        Object v = options.get(type.configKey() + "." + key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    public boolean optBool(CheckType type, String key, boolean def) {
        Object v = options.get(type.configKey() + "." + key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public long optLongOr(CheckType type, String key, long def) {
        Object v = options.get(type.configKey() + "." + key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
