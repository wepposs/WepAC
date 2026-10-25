package dev.weposs.wepac.client;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.record.RecordManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Detects which client/launcher a player connects with by sniffing the
 * vanilla "minecraft:brand" payload the client sends on join.
 *
 * Verdicts:
 *  - CHEAT:       brand matches a known cheat client -> 100% certainty (the
 *                 client literally announced itself) -> ban + record strike.
 *  - SUSPICIOUS:  brand matches the watch list, or is completely unknown ->
 *                 never ban (it may be some obscure vanilla launcher),
 *                 put the player ON RECORD and watch closely.
 *  - KNOWN:       legit clients (Vanilla, Fabric, Lunar, TLauncher, ...) ->
 *                 just report to staff ("this one plays from TLauncher").
 */
public final class ClientDetector implements PluginMessageListener {

    public static final String CHANNEL = "minecraft:brand";

    private final WepAC plugin;

    public ClientDetector(WepAC plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        if (!plugin.config().clientDetectEnabled) return;
        String brand;
        try {
            brand = readBrand(message);
        } catch (RuntimeException e) {
            return;
        }
        // plugin messages may arrive off the main thread
        Bukkit.getScheduler().runTask(plugin, () -> handle(player, brand));
    }

    private void handle(Player player, String brand) {
        PlayerData data = plugin.data().get(player);
        String normalized = brand == null ? "" : brand.trim();
        if (normalized.equalsIgnoreCase(data.clientBrand)) return; // already handled
        data.clientBrand = normalized.isEmpty() ? "?" : normalized;
        String lower = normalized.toLowerCase(Locale.ROOT);

        // 1) definitive cheat client -> ban (self-reported brand = certainty)
        for (String cheat : plugin.config().cheatBrands) {
            if (!cheat.isEmpty() && lower.contains(cheat)) {
                data.clientVerdict = "CHEAT";
                ban(player, normalized);
                return;
            }
        }
        // 2) explicitly suspicious brands -> watch
        for (String watch : plugin.config().watchBrands) {
            if (!watch.isEmpty() && lower.contains(watch)) {
                data.clientVerdict = "SUSPICIOUS";
                startWatch(player, normalized);
                return;
            }
        }
        // 3) known legit clients
        for (Map.Entry<String, String> e : plugin.config().knownBrands.entrySet()) {
            if (lower.contains(e.getKey())) {
                data.clientVerdict = "KNOWN";
                data.clientName = e.getValue();
                notifyStaff(player, e.getValue(), normalized);
                return;
            }
        }
        // 4) completely unknown brand: suspicion, never a ban
        data.clientVerdict = "UNKNOWN";
        if (plugin.config().clientWatchUnknown) {
            startWatch(player, normalized);
        } else {
            notifyStaff(player, normalized.isEmpty() ? "?" : normalized, normalized);
        }
    }

    private void ban(Player player, String brand) {
        plugin.alerts().broadcastRaw("&4&l[КЛИЕНТ]&r &c" + player.getName()
                + " &7зашёл с читерского клиента &c" + brand
                + " &7— клиент сам себя назвал, уверенность 100%, баню.");
        String cmd = plugin.config().clientBanCommand
                .replace("%player%", player.getName())
                .replace("%client%", brand);
        plugin.ai().labelFromPunishment(player);
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        plugin.records().addStrike(player, CheckType.CLIENT, true);
    }

    private void startWatch(Player player, String brand) {
        RecordManager.Status current = plugin.records().status(player.getUniqueId());
        if (current == RecordManager.Status.CLEAN) {
            plugin.records().setStatus(player, RecordManager.Status.WATCH, "client:" + brand);
        }
        plugin.alerts().broadcastRaw("&6&l[КЛИЕНТ]&r &7" + player.getName()
                + " &7зашёл с неизвестного/подозрительного клиента &6"
                + (brand.isEmpty() ? "?" : brand)
                + " &7— это может быть редкий ванильный лаунчер, поэтому НЕ бан. Слежка включена.");
    }

    private void notifyStaff(Player player, String display, String raw) {
        if (!plugin.config().clientNotifyStaff) return;
        String extra = display.equalsIgnoreCase(raw) ? "" : " &8(&7brand: " + raw + "&8)";
        plugin.alerts().broadcastRaw("&7" + player.getName() + " &8→ &fклиент: &7"
                + display + extra);
    }

    /** Payload is a varint-prefixed UTF-8 string (network format). */
    static String readBrand(byte[] data) {
        int len = 0;
        int shift = 0;
        int pos = 0;
        while (pos < data.length) {
            int b = data[pos++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 21) throw new IllegalStateException("bad varint");
        }
        if (len < 0 || len > 128) throw new IllegalStateException("bad brand length " + len);
        int avail = data.length - pos;
        return new String(data, pos, Math.min(len, avail), StandardCharsets.UTF_8);
    }
}
