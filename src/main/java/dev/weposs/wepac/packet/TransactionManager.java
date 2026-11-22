package dev.weposs.wepac.packet;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.util.Locale;

/**
 * Transaction engine: one play-phase Ping per player per tick, Pongs matched
 * in {@link TransactionState}. Consumers:
 *
 *  - ReachCheck: lag-compensation window anchored on the last ACKED tick
 *    instead of the spoofable keep-alive ping,
 *  - Horizontal physics: knockback is only "owed" once the client acked it,
 *  - BADPACKETS: pongs for ids we never sent, or answered out of order
 *    (disablers / ping-spoof modules that reorder or fabricate),
 *  - BLINK: transactions withheld while move packets still flow (selective
 *    delay of server->client acknowledgements = classic backtrack/fakelag),
 *  - Ping spoof: keep-alive ping inflated far above the transaction RTT
 *    (fake ping to farm reach/lag compensation).
 */
public final class TransactionManager implements org.bukkit.event.Listener {

    private final WepAC plugin;
    private final PacketSniffer sniffer;
    private Constructor<?> pingCtor;
    private boolean warned;

    public TransactionManager(WepAC plugin, PacketSniffer sniffer) {
        this.plugin = plugin;
        this.sniffer = sniffer;
    }

    public boolean start() {
        for (String name : new String[]{
                "net.minecraft.network.protocol.common.ClientboundPingPacket",
                "net.minecraft.network.protocol.game.ClientboundPingPacket",
                "net.minecraft.network.protocol.common.PacketPlayOutPing"}) {
            try {
                Class<?> c = Class.forName(name);
                pingCtor = c.getDeclaredConstructor(int.class);
                pingCtor.setAccessible(true);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (pingCtor == null) {
            plugin.getLogger().warning("Transaction engine INACTIVE: ping packet class not found on "
                    + "this server build (reach falls back to keep-alive compensation).");
            return false;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::sendAll, 1L, 1L);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::evaluate, 40L, 20L);
        plugin.getLogger().info("Transaction engine active (ping/pong every tick).");
        return true;
    }

    private void sendAll() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.data().getIfLoaded(p.getUniqueId());
            if (data == null) continue;
            Channel ch = sniffer.channel(p.getUniqueId());
            if (ch == null || !ch.isActive()) continue;
            try {
                int id = data.transactions.onSend(now);
                Object packet = pingCtor.newInstance(id);
                ch.writeAndFlush(packet);
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("Transaction send failed: " + t);
                }
            }
        }
    }

    /** Netty thread: called by the sniffer for every Pong packet. */
    void onPong(PlayerData data, int id) {
        if (!TransactionState.ours(id)) return; // someone else's ping (vanilla/other plugin)
        TransactionState.PongResult r = data.transactions.onPong(id, System.currentTimeMillis());
        int await = data.kbAwaitId;
        // ids count DOWN, so every id <= await was sent after the velocity packet
        if (await != 0 && id <= await && r != TransactionState.PongResult.UNKNOWN_ID) {
            data.kbAwaitId = 0;
            data.kbArmedY = data.kbAwaitY;
            data.kbArmedAt = System.currentTimeMillis();
            data.kbArmedH = data.kbAwaitH; // written last: the sniffer keys on it
        }
        if (r == TransactionState.PongResult.UNKNOWN_ID || r == TransactionState.PongResult.OUT_OF_ORDER) {
            data.transactions.badPongs++;
            data.transactions.lastBadPong = r + " id=" + id;
        }
    }

    /**
     * Velocity packet goes out right after this event; the next ping we send
     * is written behind it, so its ack proves the client RECEIVED the knockback.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(org.bukkit.event.player.PlayerVelocityEvent event) {
        PlayerData data = plugin.data().getIfLoaded(event.getPlayer().getUniqueId());
        if (data == null || !data.transactions.active) return;
        org.bukkit.util.Vector v = event.getVelocity();
        data.kbAwaitH = Math.hypot(v.getX(), v.getZ());
        data.kbAwaitY = v.getY();
        data.kbArmedH = Double.NaN; // a newer velocity supersedes an unjudged one
        data.kbAwaitId = data.transactions.peekNextId();
    }

    private void evaluate() {
        long now = System.currentTimeMillis();
        Check bad = plugin.check(CheckType.BADPACKETS);
        Check blink = plugin.check(CheckType.BLINK);
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.data().getIfLoaded(player.getUniqueId());
            if (data == null || player.hasPermission("wepac.bypass")) continue;
            TransactionState tx = data.transactions;
            if (now - data.joinTime < 5000L || now - data.lastRespawn < 5000L) {
                tx.badPongs = 0;
                continue;
            }

            if (tx.badPongs > 0) {
                if (bad != null && bad.enabled()) {
                    bad.flag(player, data, "transaction pong " + tx.lastBadPong
                            + " (" + tx.badPongs + "x) - reordered/fabricated acks", 5);
                }
                tx.badPongs = 0;
            }

            packetOrder(player, data, bad, now);
            timerAndSprint(player, data, bad, now);
            if (!tx.active) continue;
            long withheld = tx.oldestPendingAge(now);
            long holdLimit = plugin.config().optLongOr(CheckType.BLINK, "transaction-hold-ms", 2500L);
            boolean moving = now - data.lastFlyingPacket < 250L;
            if (blink != null && blink.enabled() && moving
                    && withheld > Math.max(holdLimit, (long) (tx.rttMs * 3) + 1000L)) {
                blink.flag(player, data, String.format(Locale.ROOT,
                        "transactions withheld %dms while move packets flow (fakelag/backtrack)",
                        withheld), 3);
            }

            if (bad != null && bad.enabled() && tx.rttMs >= 0) {
                int keepAlive = player.getPing();
                double spoofGap = plugin.config().optDouble(CheckType.BADPACKETS, "ping-spoof-gap", 250.0);
                // keep-alive is a slow moving average (15s probes): a single lag
                // spike can hold it high for a while, so demand a large, ratio-
                // based gap sustained for 30 evaluations
                if (keepAlive - tx.rttMs > spoofGap && keepAlive > tx.rttMs * 2.0) {
                    data.pingSpoofStreak++;
                    if (data.pingSpoofStreak >= 30) {
                        data.pingSpoofStreak = 0;
                        bad.flag(player, data, String.format(Locale.ROOT,
                                "ping spoof: keep-alive %dms vs transaction RTT %.0fms",
                                keepAlive, tx.rttMs), 3);
                    }
                } else {
                    data.pingSpoofStreak = 0;
                }
            }
        }
    }

    private void timerAndSprint(Player player, PlayerData data, Check bad, long now) {
        Check timer = plugin.check(CheckType.TIMER);
        long tb = data.timerFlagBalance;
        if (tb != 0L) {
            data.timerFlagBalance = 0L;
            if (timer != null && timer.enabled()) {
                timer.flag(player, data, String.format(Locale.ROOT,
                        "Timer balance +%dms ahead of real time (client ticks faster than 20/s)", tb), 3);
            }
        }
        // tick-end stream stopped while move packets continue: the client
        // suppresses its tick marker (timer/disabler)
        if (data.tickEndSeen && bad != null && bad.enabled()
                && now - data.lastFlyingPacket < 250L && now - data.lastTickEndAt > 1500L
                && now - data.joinTime > 5000L) {
            bad.flag(player, data, "move packets without client tick-end markers for "
                    + (now - data.lastTickEndAt) + "ms", 4);
            data.lastTickEndAt = now;
        }
        // Sprint with food <= 6 is impossible for a vanilla client
        Check speed = plugin.check(CheckType.SPEED);
        if (speed != null && speed.enabled() && player.isSprinting() && player.getFoodLevel() <= 6
                && !player.getAllowFlight() && !player.isInsideVehicle() && !player.isGliding()
                && !player.isSwimming() && now - data.lastFlyingPacket < 250L) {
            if (++data.hungerSprintStreak >= 3) {
                data.hungerSprintStreak = 0;
                speed.flag(player, data, "sprinting with food " + player.getFoodLevel()
                        + " (vanilla requires > 6)", 2);
            }
        } else {
            data.hungerSprintStreak = 0;
        }
    }

    /** Netty-collected packet-order violations (cheap, near-zero FP). */
    private void packetOrder(Player player, PlayerData data, Check bad, long now) {
        Check aura = plugin.check(CheckType.KILLAURA);
        int proto = dev.weposs.wepac.util.LegacyProtocol.playerVersion(player.getUniqueId());
        if ((proto != -1 && proto <= dev.weposs.wepac.util.LegacyProtocol.PROTOCOL_1_8)
                || dev.weposs.wepac.util.Bedrock.isBedrock(player.getUniqueId())) {
            // 1.8 swings BEFORE attacking and Geyser synthesises packets:
            // packet-order rules do not hold for translated clients
            data.noSwingEvents = 0;
            data.multiTargetEvents = 0;
            data.duplicateSlotEvents = 0;
            aura = null;
            bad = null;
        }
        if (dev.weposs.wepac.util.Compat.holdingSpear(player)) data.lastSpearHeldAt = now;
        if (now - data.lastSpearHeldAt < 2000L) {
            // 1.21.11 spear jab pierces: several entities in one tick, and the
            // kinetic charge damages without the classic attack+swing pair
            data.noSwingEvents = 0;
            data.multiTargetEvents = 0;
        }
        if (data.noSwingEvents > 0) {
            int n = data.noSwingEvents;
            data.noSwingEvents = 0;
            // vanilla ALWAYS swings after an attack in the same tick; allow one
            // stray per window
            if (n >= 2 && aura != null && aura.enabled()) {
                aura.flag(player, data, "NoSwing: " + n + " attacks without arm swing", 4);
            }
        }
        if (data.multiTargetEvents > 0) {
            int n = data.multiTargetEvents;
            data.multiTargetEvents = 0;
            if (aura != null && aura.enabled()) {
                aura.flag(player, data, "MultiAura: attacked " + (n + 1)
                        + "+ different entities inside one client tick", 5);
            }
        }
        if (data.duplicateSlotEvents > 0) {
            int n = data.duplicateSlotEvents;
            data.duplicateSlotEvents = 0;
            if (n >= 2 && bad != null && bad.enabled()) {
                bad.flag(player, data, "held-slot packet repeated the current slot " + n + "x", 2);
            }
        }
        // knockback acked but no position packet for 600ms: the client stood
        // perfectly still through a hit = full anti-KB
        double armed = data.kbArmedH;
        if (!Double.isNaN(armed) && now - data.kbArmedAt > 600L) {
            data.kbArmedH = Double.NaN;
            Check kb = plugin.check(CheckType.VELOCITY);
            if (armed > 0.25 && kb != null && kb.enabled() && !player.isBlocking()
                    && now - data.lastTeleport > 1000L) {
                kb.flag(player, data, String.format(Locale.ROOT,
                        "acked %.2f b/t knockback but sent no movement for 600ms", armed), 3);
            }
        }
    }

    /**
     * Effective latency for lag compensation: the transaction RTT when known
     * (unspoofable), otherwise the keep-alive ping.
     */
    public static int latency(Player player, PlayerData data) {
        TransactionState tx = data.transactions;
        if (tx.active && tx.rttMs >= 0) return (int) Math.round(tx.rttMs);
        return player.getPing();
    }
}
