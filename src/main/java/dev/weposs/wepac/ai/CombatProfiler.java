package dev.weposs.wepac.ai;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.config.ConfigManager;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.record.RecordManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns HOW HONEST PLAYERS FIGHT from the server's real PvP.
 *
 * Every player-vs-player hit opens/extends a combat session for BOTH fighters.
 * While the session is live, the 15-feature telemetry vector is snapshotted
 * periodically (during actual combat, not after it). When a session ends
 * (no PvP hits for the timeout), the player may donate their snapshot to the
 * AI replay buffer labelled LEGIT - but only through a triple purity gate:
 *
 *   1. zero active violations (no rule flagged them this session),
 *   2. police record status CLEAN (not watched, not wanted),
 *   3. AI probability below pvp-max-probability (the model itself is not suspicious).
 *
 * Plus the session must be a real fight: min duration and min hit count,
 * and donations are rate-limited per player.
 *
 * Anti-poisoning: every donated sample is tagged with the donor's UUID. When
 * a donor is later punished, RecordManager triggers AIBrain.purgeSamplesFrom()
 * and all of their past "legit" donations are removed - a cheater who once
 * looked clean cannot permanently poison the model.
 */
public final class CombatProfiler implements Listener {

    private static final class Session {
        long startTime;
        long lastHitTime;
        long lastCapture;
        int hits;
        double[] snapshot; // raw features captured during active combat
    }

    private final WepAC plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDonation = new ConcurrentHashMap<>();

    public CombatProfiler(WepAC plugin) {
        this.plugin = plugin;
        // evaluate finished sessions every 2 seconds
        Bukkit.getScheduler().runTaskTimer(plugin, this::evaluateSessions, 40L, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpHit(EntityDamageByEntityEvent event) {
        ConfigManager cfg = plugin.config();
        if (!cfg.aiEnabled || !cfg.pvpLearningEnabled) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (victim.equals(attacker)) return;
        touch(attacker);
        touch(victim);
    }

    private void touch(Player player) {
        if (player.hasPermission("wepac.bypass")) return;
        long now = System.currentTimeMillis();
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            s = new Session();
            s.startTime = now;
            sessions.put(player.getUniqueId(), s);
        }
        s.lastHitTime = now;
        s.hits++;
        // snapshot telemetry while the fight is actually happening
        if (s.snapshot == null || now - s.lastCapture >= 2500L) {
            s.lastCapture = now;
            s.snapshot = plugin.ai().buildRawFeatures(player, plugin.data().get(player));
        }
    }

    private void evaluateSessions() {
        ConfigManager cfg = plugin.config();
        if (!cfg.aiEnabled || !cfg.pvpLearningEnabled) {
            sessions.clear();
            return;
        }
        long now = System.currentTimeMillis();
        long timeoutMs = 1000L * cfg.pvpSessionTimeoutSeconds;
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Session s = entry.getValue();
            if (now - s.lastHitTime < timeoutMs) continue;
            it.remove();
            try {
                consider(entry.getKey(), s, now);
            } catch (Throwable t) {
                plugin.debug("CombatProfiler error: " + t);
            }
        }
    }

    private void consider(UUID id, Session s, long now) {
        ConfigManager cfg = plugin.config();
        if (s.snapshot == null) return;

        long durationMs = s.lastHitTime - s.startTime;
        if (durationMs < 1000L * cfg.pvpMinSessionSeconds) return;
        if (s.hits < cfg.pvpMinHits) return;

        Player player = Bukkit.getPlayer(id);
        PlayerData data = plugin.data().getIfLoaded(id);
        if (player == null || data == null) return;

        // triple purity gate
        if (data.totalVl() > 0) return;
        if (plugin.records().status(id) != RecordManager.Status.CLEAN) return;
        if (data.aiProbability > cfg.pvpMaxAiProbability) return;

        // rate-limit donations per player
        long minInterval = 3_600_000L / Math.max(1, cfg.pvpMaxSamplesPerHour);
        Long last = lastDonation.get(id);
        if (last != null && now - last < minInterval) return;
        lastDonation.put(id, now);

        plugin.ai().addSample(s.snapshot, 0.0, "pvp-legit:" + player.getName(), id);
    }
}
