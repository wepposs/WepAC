package dev.weposs.wepac;

import dev.weposs.wepac.ai.NeuralNetwork;
import dev.weposs.wepac.client.ClientDetector;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.packet.PacketPhysics;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server-free test suite for WepAC's pure logic cores. Runs as part of
 * {@code mvn package} (surefire); no Minecraft server is needed.
 *
 * Coverage:
 *  - PacketPhysics.Sim: per-packet gravity simulation - legit vanilla arcs must
 *    NEVER flag, glide/hover/air-jump/slow-climb MUST flag, external impulses
 *    and network bursts must NOT flag, landings and stream stalls must resync.
 *  - NeuralNetwork: converges on a separable problem; save/load roundtrip
 *    preserves predictions and train-step counter.
 *  - ClientDetector: collision-safe brand matching and the varint brand parser.
 *  - PlayerData stats: avg/stddev every buffer check relies on.
 */
public class SelfTest {

    static final double LIMIT = 0.35;   // config default divergence-limit
    static final double JUMP_VY = 0.42; // no jump boost

    /** One airborne physics step: vanilla vy = (vy - 0.08) * 0.98, dy = vy. */
    static double stepVy(double vy) {
        return (vy - PacketPhysics.GRAVITY) * PacketPhysics.DRAG;
    }

    // ------------------------------------------------------------------
    // PacketPhysics.Sim
    // ------------------------------------------------------------------

    @Test
    public void simVanillaJumpNeverFlags() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 64.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);   // grounded seed
        double vy = JUMP_VY;
        for (int i = 0; i < 60; i++) {
            t += 50;
            y += vy;
            vy = stepVy(vy);
            double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
            assertEquals("vanilla jump arc flagged at packet " + i, 0.0, over, 1e-12);
            if (y <= 64.0) break; // descended back to ground level
        }
    }

    @Test
    public void simLedgeFallNeverFlags() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        double vy = 0.0; // walk off: no impulse
        for (int i = 0; i < 40; i++) {
            t += 50;
            vy = stepVy(vy);
            y += vy;
            double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
            assertEquals("free fall flagged at packet " + i, 0.0, over, 1e-12);
        }
    }

    @Test
    public void simJumpLandCyclesNeverFlag() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        for (int cycle = 0; cycle < 5; cycle++) {
            double y = 64.0;
            double vy = JUMP_VY;
            sim.onPacket(y, t, true, JUMP_VY, LIMIT);
            while (true) {
                t += 50;
                y += vy;
                vy = stepVy(vy);
                boolean landed = y <= 64.0;
                double over = sim.onPacket(landed ? 64.0 : y, t, landed, JUMP_VY, LIMIT);
                assertEquals("bhop cycle " + cycle + " flagged", 0.0, over, 1e-12);
                if (landed) break;
            }
        }
    }

    @Test
    public void simGlideFlags() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        // walk off ledge, first fall packet anchors the baseline
        t += 50;
        y += stepVy(0.0);
        sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        // now glide: descend at a constant -0.01/tick instead of accelerating
        int flags = 0;
        for (int i = 0; i < 30; i++) { // 1.5s
            t += 50;
            y -= 0.01;
            if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
        }
        assertTrue("glide was never flagged in 1.5s (div=" + sim.divergence + ")", flags >= 1);
    }

    @Test
    public void simHoverFlags() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 64.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        // jump, then freeze altitude
        t += 50;
        y += JUMP_VY;
        sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        int flags = 0;
        for (int i = 0; i < 40; i++) { // 2s
            t += 50;
            if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
        }
        assertTrue("hover was never flagged in 2s (div=" + sim.divergence + ")", flags >= 1);
    }

    @Test
    public void simAirJumpFlags() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        double vy = 0.0;
        for (int i = 0; i < 5; i++) { // fall a few ticks
            t += 50;
            vy = stepVy(vy);
            y += vy;
            sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        }
        // double jump: sudden +0.42 burst mid-air
        t += 50;
        y += 0.42;
        double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        assertTrue("air-jump burst was not flagged (div=" + sim.divergence + ")", over > 0.0);
    }

    @Test
    public void simSlowClimbFlags() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 64.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        // jump, then climb slowly at +0.1/tick (gravity-fly signature)
        t += 50;
        y += JUMP_VY;
        sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        int flags = 0;
        for (int i = 0; i < 40; i++) {
            t += 50;
            y += 0.1;
            if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
        }
        assertTrue("slow climb never flagged (div=" + sim.divergence + ")", flags >= 1);
    }

    @Test
    public void simImpulseNoFlagKeepsCharge() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        double vy = 0.0;
        double divBefore = 0.0;
        for (int i = 0; i < 8; i++) { // fall until fast
            t += 50;
            vy = stepVy(vy);
            y += vy;
            sim.onPacket(y, t, false, JUMP_VY, LIMIT);
            divBefore = sim.divergence;
        }
        // explosion launches the player upward by +1.5 in one packet
        t += 50;
        y += 1.5;
        double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        assertEquals("explosion impulse was flagged", 0.0, over, 1e-12);
        assertEquals("impulse must keep accumulated charge", divBefore, sim.divergence, 1e-9);
        assertFalse("impulse must re-anchor the baseline", Double.isNaN(sim.lastDy));
    }

    @Test
    public void simConsecutiveImpulsesFlag() {
        // A17: an accelerating climb (every packet looks like "an explosion")
        // must not ride the impulse re-anchor forever - the SECOND consecutive
        // impulse charges in full
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        double vy = 0.0;
        for (int i = 0; i < 5; i++) { // fall a bit
            t += 50;
            vy = stepVy(vy);
            y += vy;
            sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        }
        t += 50;
        y += 3.0; // impulse #1: re-anchor, no charge
        double over1 = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        assertEquals("single impulse must not flag", 0.0, over1, 1e-12);
        assertEquals("single impulse must not charge", 0.0, sim.divergence, 1e-9);
        t += 50;
        y += 5.0; // impulse #2, consecutive: charged in full -> overflow
        double over2 = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        assertTrue("second consecutive impulse must flag (over=" + over2
                + ", div=" + sim.divergence + ")", over2 > 0.0);
    }

    @Test
    public void simBurstFlushNoCharge() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        double vy = 0.0;
        for (int i = 0; i < 3; i++) {
            t += 50;
            vy = stepVy(vy);
            y += vy;
            sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        }
        // network hiccup: three full-size fall packets arrive back-to-back (dt=0)
        for (int i = 0; i < 3; i++) {
            vy = stepVy(vy);
            y += vy;
            double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT); // same timestamp
            assertEquals("burst flush packet flagged", 0.0, over, 1e-12);
        }
        assertEquals("burst flush must not charge divergence", 0.0, sim.divergence, 1e-9);
    }

    @Test
    public void simGapResyncs() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        t += 50;
        y -= 0.0784;
        sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        // 6s of nothing (freeze/world load), then a packet far below
        t += 6000;
        y -= 3.0;
        double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        assertEquals("stalled stream flagged after resync", 0.0, over, 1e-12);
        assertEquals("stalled stream must reset divergence", 0.0, sim.divergence, 1e-9);
    }

    @Test
    public void simBlinkPulseGlideFlags() {
        // a glide cheat pulsing packets with ~600ms gaps must NOT be able to
        // live in the blind spot: gaps re-anchor the velocity baseline but the
        // accumulated charge survives
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 80.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        t += 50;
        y += stepVy(0.0);
        sim.onPacket(y, t, false, JUMP_VY, LIMIT); // walk-off anchor
        int flags = 0;
        for (int cycle = 0; cycle < 6; cycle++) {
            for (int i = 0; i < 5; i++) { // glide a bit
                t += 50;
                y -= 0.01;
                if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
            }
            t += 600; // blink gap
            y -= 0.01;
            if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
        }
        assertTrue("blink-pulsed glide was never flagged", flags >= 1);
    }

    @Test
    public void simStalledLegitFallNoFlag() {
        // a legit free fall interrupted by 600ms network stalls must never flag:
        // the re-anchor adopts the observed velocity after every gap
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 200.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        double vy = 0.0;
        int flags = 0;
        for (int cycle = 0; cycle < 20; cycle++) {
            for (int i = 0; i < 5; i++) { // honest physics
                t += 50;
                vy = stepVy(vy);
                y += vy;
                if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
            }
            // network stall: the client kept simulating, 12 ticks pass at once
            for (int i = 0; i < 12; i++) {
                vy = stepVy(vy);
                y += vy;
            }
            t += 600;
            if (sim.onPacket(y, t, false, JUMP_VY, LIMIT) > 0.0) flags++;
        }
        assertEquals("stalled legit fall flagged " + flags + " times", 0, flags);
    }

    @Test
    public void simLandingResets() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 64.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        t += 50;
        y += JUMP_VY;
        sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        for (int i = 0; i < 4; i++) { // bank some divergence by hovering briefly
            t += 50;
            sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        }
        // landing (server truth grounded) must wipe the state
        t += 50;
        sim.onPacket(64.0, t, true, JUMP_VY, LIMIT);
        assertEquals("landing did not reset divergence", 0.0, sim.divergence, 1e-9);
        assertTrue("landing did not restore grounded state", sim.wasGrounded);
    }

    @Test
    public void simSuperJumpChargesExcess() {
        PacketPhysics.Sim sim = new PacketPhysics.Sim();
        long t = 0;
        double y = 64.0;
        sim.onPacket(y, t, true, JUMP_VY, LIMIT);
        // takeoff with a 0.9 impulse (launch-module "jump"): the excess over the
        // vanilla impulse (0.48) immediately breaches the 0.35 limit -> overflow
        t += 50;
        y += 0.9;
        double over = sim.onPacket(y, t, false, JUMP_VY, LIMIT);
        assertEquals("takeoff excess must overflow", 0.9 - JUMP_VY, over, 1e-9);
    }

    @Test
    public void simExpectedDisplacement() {
        assertEquals("1-tick displacement", stepVy(0.42),
                PacketPhysics.Sim.expectedDisplacement(0.42, 1), 1e-12);
        double v = stepVy(0.42);
        assertEquals("2-tick displacement", stepVy(0.42) + stepVy(v),
                PacketPhysics.Sim.expectedDisplacement(0.42, 2), 1e-12);
        assertEquals("0-tick displacement", 0.0,
                PacketPhysics.Sim.expectedDisplacement(0.3, 0), 1e-12);
    }

    // ------------------------------------------------------------------
    // Mouse GCD (KillAura rotation quantisation)
    // ------------------------------------------------------------------

    static float wrapDeg(float d) {
        d %= 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    /** Fold the sample list exactly like checkMouseGcd/checkMouseGcdYaw do. */
    static double foldedGcd(java.util.List<Double> deltas) {
        double g = 0;
        for (double d : deltas) {
            g = g == 0 ? d : dev.weposs.wepac.checks.combat.KillAuraCheck.gcd(g, d);
            if (g < 1e-6) break;
        }
        return g;
    }

    @Test
    public void gcdHonestMouseConverges() {
        // honest deltas are k*sensStep accumulated in FLOAT (client rounding noise
        // ~1e-5). The old absolute-stop Euclid collapsed to the noise floor and
        // flagged ~65-100% of such runs; with the relative tolerance the folded
        // GCD must stay at/above the mouse-step for virtually every run.
        double[] sens = {0.0225, 0.045, 0.0833, 0.15};
        Random rnd = new Random(11);
        int falseFlags = 0;
        int runs = 0;
        for (double s : sens) {
            for (int run = 0; run < 75; run++) {
                runs++;
                float yaw = wrapDeg(rnd.nextFloat() * 360f - 180f);
                java.util.List<Double> deltas = new java.util.ArrayList<>();
                while (deltas.size() < 40) {
                    int k = 1 + rnd.nextInt(30);
                    float next = wrapDeg((float) (yaw + (float) (k * s)));
                    double d = Math.abs((double) next - yaw);
                    yaw = next;
                    if (d > 0.05 && d < 50.0) deltas.add(d);
                }
                if (foldedGcd(deltas) < 0.005) falseFlags++;
            }
        }
        assertTrue("honest mouse false-flagged in " + falseFlags + "/" + runs
                + " runs (expected <= 2)", falseFlags <= 2);
    }

    @Test
    public void gcdRawDoublesCollapse() {
        // raw doubles with no common quantiser (aim modules writing computed
        // rotations) must collapse below gcd-min in the overwhelming majority
        Random rnd = new Random(11);
        int flagged = 0;
        int runs = 300;
        for (int run = 0; run < runs; run++) {
            java.util.List<Double> deltas = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) deltas.add(0.05 + rnd.nextDouble() * 20);
            if (foldedGcd(deltas) < 0.005) flagged++;
        }
        assertTrue("raw doubles detected only " + flagged + "/" + runs
                + " (expected >= 270)", flagged >= 270);
    }

    // ------------------------------------------------------------------
    // NeuralNetwork
    // ------------------------------------------------------------------

    @Test
    public void nnLearnsSeparableProblem() {
        NeuralNetwork net = new NeuralNetwork(2, 4, 1);
        net.setLearningRate(0.3);
        double[][] in = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};
        double[] target = {0, 0, 0, 1};
        for (int epoch = 0; epoch < 3000; epoch++) {
            for (int i = 0; i < 4; i++) {
                net.train(in[i], target[i]);
            }
        }
        assertTrue("AND(0,0) must be < 0.5, got " + net.predict(in[0]), net.predict(in[0]) < 0.5);
        assertTrue("AND(0,1) must be < 0.5, got " + net.predict(in[1]), net.predict(in[1]) < 0.5);
        assertTrue("AND(1,0) must be < 0.5, got " + net.predict(in[2]), net.predict(in[2]) < 0.5);
        assertTrue("AND(1,1) must be > 0.5, got " + net.predict(in[3]), net.predict(in[3]) > 0.5);
        assertEquals("trainSteps must be 12000", 12000, net.trainSteps());
    }

    @Test
    public void nnPersistenceRoundtrip() throws Exception {
        NeuralNetwork net = new NeuralNetwork(18, 16, 8, 1); // production shape
        net.setLearningRate(0.05);
        Random rnd = new Random(42);
        for (int i = 0; i < 500; i++) {
            double[] v = new double[18];
            for (int j = 0; j < 18; j++) v[j] = rnd.nextGaussian();
            net.train(v, rnd.nextBoolean() ? 1.0 : 0.0);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            net.save(out);
        }
        NeuralNetwork copy = NeuralNetwork.load(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("inputSize must survive roundtrip", 18, copy.inputSize());
        assertEquals("trainSteps must survive roundtrip", net.trainSteps(), copy.trainSteps());
        for (int i = 0; i < 50; i++) {
            double[] v = new double[18];
            for (int j = 0; j < 18; j++) v[j] = rnd.nextGaussian();
            assertEquals("prediction must survive roundtrip",
                    net.predict(v), copy.predict(v), 1e-12);
        }
    }

    // ------------------------------------------------------------------
    // PacketSniffer position gating (1.21.2+ parent-class x/y/z trap)
    // ------------------------------------------------------------------

    @Test
    public void positionBearingGating() {
        // Mojang mappings (1.21.2+: hasPos lives on the parent, Rot/Status return 0.0!)
        String pos = "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos";
        String posRot = "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$PosRot";
        String rot = "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Rot";
        String status = "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$StatusOnly";
        assertTrue("Pos must be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(pos, Boolean.TRUE));
        assertTrue("PosRot must be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(posRot, Boolean.TRUE));
        assertFalse("Pos with hasPos=false must NOT be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(pos, Boolean.FALSE));
        assertFalse("Rot must NOT be consumed even if fields resolve",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(rot, Boolean.TRUE));
        assertFalse("Status must NOT be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(status, Boolean.TRUE));
        // Spigot mappings (no hasPos field - class name alone decides)
        String sPos = "net.minecraft.network.protocol.game.PacketPlayInFlying$PacketPlayInPosition";
        String sPosLook = "net.minecraft.network.protocol.game.PacketPlayInFlying$PacketPlayInPositionLook";
        String sLook = "net.minecraft.network.protocol.game.PacketPlayInFlying$PacketPlayInLook";
        String sFlying = "net.minecraft.network.protocol.game.PacketPlayInFlying";
        assertTrue("Spigot Position must be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(sPos, null));
        assertTrue("Spigot PositionLook must be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(sPosLook, null));
        assertFalse("Spigot Look must NOT be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(sLook, null));
        assertFalse("Spigot bare Flying must NOT be consumed",
                dev.weposs.wepac.packet.PacketSniffer.positionBearing(sFlying, null));
    }

    // ------------------------------------------------------------------
    // ClientDetector
    // ------------------------------------------------------------------

    @Test
    public void brandExactToken() {
        assertTrue("token match", ClientDetector.matchBrand("wurst client", "wurst"));
        assertTrue("whole-brand token match", ClientDetector.matchBrand("wurst", "wurst"));
        assertTrue("token inside brand", ClientDetector.matchBrand("the wurst 2.0", "wurst"));
        assertTrue("delta token match", ClientDetector.matchBrand("delta client", "delta"));
    }

    @Test
    public void brandNoCollisions() {
        assertFalse("ghost must not match GhostlyCraft", ClientDetector.matchBrand("ghostlycraft", "ghost"));
        assertFalse("delta must not match Deltacraft", ClientDetector.matchBrand("deltacraft", "delta"));
        assertFalse("wave must not match sunwave", ClientDetector.matchBrand("sunwave modpack", "wave"));
        assertFalse("peak must not match peakybuild", ClientDetector.matchBrand("peakybuild", "peak"));
        assertFalse("unrelated brand must not match", ClientDetector.matchBrand("fabric", "wurst"));
    }

    @Test
    public void brandPrefixMatch() {
        assertTrue("6-char prefix match", ClientDetector.matchBrand("meteorclient", "meteor"));
        assertTrue("6-char exact match", ClientDetector.matchBrand("meteor", "meteor"));
        assertTrue("7-char prefix match", ClientDetector.matchBrand("vesence 1.21", "vesence"));
        assertFalse("prefix must respect token start only", ClientDetector.matchBrand("metropolis", "meteor"));
    }

    @Test
    public void brandLongSubstring() {
        assertTrue("8+ substring match", ClientDetector.matchBrand("liquidbounce++", "liquidbounce"));
        assertTrue("8+ substring inside", ClientDetector.matchBrand("my liquidbounce fork", "liquidbounce"));
        assertFalse("needle longer than brand", ClientDetector.matchBrand("liquid", "liquidbounce"));
    }

    static byte[] varintBrand(String s) {
        byte[] str = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = str.length;
        while ((len & ~0x7F) != 0) {
            out.write((len & 0x7F) | 0x80);
            len >>>= 7;
        }
        out.write(len);
        out.write(str, 0, str.length);
        return out.toByteArray();
    }

    @Test
    public void brandVarintParsing() {
        assertEquals("simple brand", "vanilla", ClientDetector.readBrand(varintBrand("vanilla")));
        assertEquals("brand with space", "Lunar Client",
                ClientDetector.readBrand(varintBrand("Lunar Client")));
        // truncated payload: returns what is available, must not throw
        byte[] full = varintBrand("tlauncher");
        byte[] cut = new byte[full.length - 4];
        System.arraycopy(full, 0, cut, 0, cut.length);
        assertEquals("truncated brand", "tlaun", ClientDetector.readBrand(cut));
        // absurd declared length must be rejected
        byte[] evil = new byte[]{(byte) 0xC8, 0x01, 'a'}; // varint 200
        try {
            ClientDetector.readBrand(evil);
            throw new AssertionError("oversized brand length must throw");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------
    // Stats helpers
    // ------------------------------------------------------------------

    @Test
    public void statsHelpers() {
        assertEquals("avg", 2.0, PlayerData.avg(new double[]{1, 2, 3}), 1e-12);
        assertEquals("avg of empty", 0.0, PlayerData.avg(new double[]{}), 1e-12);
        assertEquals("sample stddev", 1.0, PlayerData.stddev(new double[]{1, 2, 3}), 1e-12);
        assertEquals("stddev of one element", 0.0, PlayerData.stddev(new double[]{5}), 1e-12);
        assertEquals("stddev of constants (robotic pacing)", 0.0,
                PlayerData.stddev(new double[]{7, 7, 7}), 1e-12);
    }
}
