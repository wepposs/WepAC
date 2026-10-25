package dev.weposs.wepac;

import dev.weposs.wepac.ai.AIBrain;
import dev.weposs.wepac.alert.AlertManager;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.checks.block.FastBreakCheck;
import dev.weposs.wepac.checks.block.FastEatCheck;
import dev.weposs.wepac.checks.block.FastPlaceCheck;
import dev.weposs.wepac.checks.block.FastRegenCheck;
import dev.weposs.wepac.checks.block.ScaffoldCheck;
import dev.weposs.wepac.checks.combat.AutoClickerCheck;
import dev.weposs.wepac.checks.combat.FastBowCheck;
import dev.weposs.wepac.checks.combat.KillAuraCheck;
import dev.weposs.wepac.checks.combat.ReachCheck;
import dev.weposs.wepac.checks.movement.AirJumpCheck;
import dev.weposs.wepac.checks.movement.FastClimbCheck;
import dev.weposs.wepac.checks.movement.FlyCheck;
import dev.weposs.wepac.checks.movement.JesusCheck;
import dev.weposs.wepac.checks.movement.MovementMonitor;
import dev.weposs.wepac.checks.movement.NoFallCheck;
import dev.weposs.wepac.checks.movement.PhaseCheck;
import dev.weposs.wepac.checks.movement.SpeedCheck;
import dev.weposs.wepac.checks.movement.WallClimbCheck;
import dev.weposs.wepac.command.ACCommand;
import dev.weposs.wepac.config.ConfigManager;
import dev.weposs.wepac.data.DataManager;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.listener.ConnectionListener;
import dev.weposs.wepac.punish.PunishmentManager;
import dev.weposs.wepac.record.RecordManager;
import dev.weposs.wepac.violation.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class WepAC extends JavaPlugin {

    private static WepAC instance;

    private ConfigManager configManager;
    private DataManager dataManager;
    private AlertManager alertManager;
    private ViolationManager violationManager;
    private PunishmentManager punishmentManager;
    private RecordManager recordManager;
    private AIBrain aiBrain;
    private dev.weposs.wepac.packet.PacketSniffer packetSniffer;
    private final List<Check> checks = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();

        dataManager = new DataManager();
        alertManager = new AlertManager(this);
        punishmentManager = new PunishmentManager(this);
        violationManager = new ViolationManager(this);
        recordManager = new RecordManager(this);
        aiBrain = new AIBrain(this);

        if (configManager.recordEnabled) {
            try {
                recordManager.load();
            } catch (Throwable t) {
                getLogger().severe("Could not load records.yml (continuing with empty records): " + t);
            }
        }
        if (configManager.aiEnabled) {
            try {
                aiBrain.load();
            } catch (Throwable t) {
                getLogger().severe("Could not load AI model (continuing with a fresh model): " + t);
            }
        }

        registerChecks();
        registerListeners();
        startTasks();

        if (configManager.clientDetectEnabled) {
            try {
                new dev.weposs.wepac.client.ClientDetector(this).register();
            } catch (Throwable t) {
                getLogger().severe("Client detector failed to register (disabled): " + t);
            }
        }

        if (configManager.packetEnabled) {
            try {
                packetSniffer = new dev.weposs.wepac.packet.PacketSniffer(this);
                packetSniffer.start();
            } catch (Throwable t) {
                packetSniffer = null;
                getLogger().severe("Packet sniffer failed to start (packet checks disabled): " + t);
            }
        }

        var command = getCommand("wepac");
        if (command != null) {
            ACCommand executor = new ACCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("WepAC enabled - " + checks.size() + " checks, AI "
                + (configManager.aiEnabled ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        if (packetSniffer != null) {
            packetSniffer.stop();
        }
        if (aiBrain != null && configManager != null && configManager.aiEnabled) {
            aiBrain.save();
        }
        if (recordManager != null && configManager != null && configManager.recordEnabled) {
            recordManager.save();
        }
        getLogger().info("WepAC disabled.");
    }

    private void registerChecks() {
        checks.clear();
        checks.add(new FlyCheck(this));
        checks.add(new SpeedCheck(this));
        checks.add(new JesusCheck(this));
        checks.add(new NoFallCheck(this));
        checks.add(new FastClimbCheck(this));
        checks.add(new WallClimbCheck(this));
        checks.add(new AirJumpCheck(this));
        checks.add(new PhaseCheck(this));
        checks.add(new dev.weposs.wepac.checks.movement.BoatFlyCheck(this));
        checks.add(new dev.weposs.wepac.checks.movement.SilentRotCheck(this));
        checks.add(new ReachCheck(this));
        checks.add(new AutoClickerCheck(this));
        checks.add(new KillAuraCheck(this));
        checks.add(new dev.weposs.wepac.checks.combat.CriticalsCheck(this));
        checks.add(new FastBowCheck(this));
        checks.add(new FastBreakCheck(this));
        checks.add(new FastPlaceCheck(this));
        checks.add(new ScaffoldCheck(this));
        checks.add(new FastRegenCheck(this));
        checks.add(new FastEatCheck(this));
        checks.add(new dev.weposs.wepac.checks.player.InventoryMoveCheck(this));
        checks.add(new dev.weposs.wepac.checks.player.ChestStealerCheck(this));
        checks.add(new dev.weposs.wepac.checks.player.XrayCheck(this));
        // flagging driven by MovementMonitor / PacketSniffer
        for (CheckType t : new CheckType[]{CheckType.STEP, CheckType.NOSLOW, CheckType.WATERWALK,
                CheckType.ELYTRAFLY, CheckType.AIRSTUCK, CheckType.LONGJUMP, CheckType.NOWEB,
                CheckType.TIMER, CheckType.BLINK, CheckType.BADPACKETS, CheckType.GROUND_SPOOF,
                CheckType.VELOCITY}) {
            checks.add(new dev.weposs.wepac.check.SimpleCheck(this, t));
        }
    }

    private void registerListeners() {
        for (Check check : checks) {
            Bukkit.getPluginManager().registerEvents(check, this);
        }
        Bukkit.getPluginManager().registerEvents(new MovementMonitor(this), this);
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(this), this);
        if (configManager.aiEnabled) {
            Bukkit.getPluginManager().registerEvents(
                    new dev.weposs.wepac.ai.CombatProfiler(this), this);
        }
    }

    private void startTasks() {
        // 20Hz position history for lag-compensated reach
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData d = dataManager.get(p);
                org.bukkit.Location loc = p.getLocation();
                d.posHistory.addLast(new double[]{now, loc.getX(), loc.getY(), loc.getZ()});
                while (!d.posHistory.isEmpty()
                        && now - d.posHistory.peekFirst()[0] > 2000L) {
                    d.posHistory.pollFirst();
                }
            }
        }, 1L, 1L);

        // VL decay - runs on the MAIN thread: it mutates PlayerData.violations
        // (EnumMap) and firedStages, which the main thread also reads/writes.
        // Running this async would race with the checks. The work is trivial.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int amount = configManager.decayAmount();
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData d = dataManager.getIfLoaded(p.getUniqueId());
                if (d == null) continue;
                if (System.currentTimeMillis() - d.lastViolationTime < 10_000L) continue;
                d.decayVl(amount);
            }
        }, 20L * configManager.decayInterval(), 20L * configManager.decayInterval());

        if (!configManager.aiEnabled) return;

        long scoreTicks = 20L * Math.max(1, configManager.aiScoreInterval);
        // AI scoring + clean-play labeling (main thread; NN is tiny)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("wepac.bypass")) continue;
                PlayerData d = dataManager.get(p);
                double prob = aiBrain.score(p, d);
                aiBrain.checkCleanPlay(p, d);

                if (prob >= configManager.aiAlertThreshold
                        && System.currentTimeMillis() - d.lastAiAlert > 30_000L) {
                    d.lastAiAlert = System.currentTimeMillis();
                    alertManager.broadcastRaw(String.format(
                            "&cAI&7: %s &7вероятность чита &c%.0f%% &8(&7VL %d&8)",
                            p.getName(), prob * 100, d.totalVl()));
                }
                if (configManager.aiPunishByAi && prob >= configManager.aiPunishProbability
                        && !p.hasPermission("wepac.bypass")) {
                    punishmentManager.execute(p, CheckType.AI, (int) (prob * 100),
                            "kick %player% &c[WepAC] AI: unfair advantage (%vl%%)");
                }
            }
        }, scoreTicks, scoreTicks);

        // Online learning (async)
        long trainTicks = 20L * configManager.aiTrainInterval;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, aiBrain::trainRound,
                trainTicks, trainTicks);

        // Periodic model save (async)
        long saveTicks = 20L * 60L * configManager.aiSaveIntervalMinutes;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, aiBrain::save, saveTicks, saveTicks);
    }

    public void reload() {
        configManager.load();
        if (aiBrain != null) {
            aiBrain.save();
        }
    }

    public List<Check> checks() {
        return checks;
    }

    public Check check(CheckType type) {
        for (Check c : checks) {
            if (c.type() == type) return c;
        }
        return null;
    }

    public ConfigManager config() {
        return configManager;
    }

    public DataManager data() {
        return dataManager;
    }

    public AlertManager alerts() {
        return alertManager;
    }

    public ViolationManager violations() {
        return violationManager;
    }

    public PunishmentManager punishments() {
        return punishmentManager;
    }

    public RecordManager records() {
        return recordManager;
    }

    public dev.weposs.wepac.packet.PacketSniffer packets() {
        return packetSniffer;
    }

    public AIBrain ai() {
        return aiBrain;
    }

    public void debug(String msg) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[debug] " + msg);
        }
    }

    public static WepAC get() {
        return instance;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
