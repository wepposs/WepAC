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
 * The brand is SELF-REPORTED: a cheat can always claim "vanilla" and slip
 * past this detector (behavioural checks still apply), but no legitimate
 * client carries a cheat's brand string - so a positive match is a sound
 * ban trigger, while absence of one proves nothing.
 *
 * Verdicts:
 *  - CHEAT:       brand matches a known cheat client (it literally announced
 *                 itself) -> ban + record strike.
 *  - SUSPICIOUS:  brand matches the watch list, or is completely unknown ->
 *                 never ban (it may be some obscure vanilla launcher),
 *                 put the player ON RECORD and watch closely.
 *  - KNOWN:       legit clients (Vanilla, Fabric, Lunar, TLauncher, ...) ->
 *                 just report to staff ("this one plays from TLauncher").
 *
 * Matching ({@link #matchBrand}) is collision-safe by needle length:
 * long needles (>= 8 chars, e.g. "liquidbounce") match as substrings;
 * 6-7 char needles match a token prefix; short ones (<= 5, e.g. "ghost")
 * require an exact whole token, so a "GhostlyCraft" modpack is never
 * mistaken for the Ghost client.
 */
public final class ClientDetector implements PluginMessageListener, org.bukkit.event.Listener {

    public static final String CHANNEL = "minecraft:brand";

    private final WepAC plugin;
    private int joins = 0;
    private int brandsSeen = 0;
    private boolean inactivityWarned = false;

    public ClientDetector(WepAC plugin) {
        this.plugin = plugin;
    }

    public void register() {
        // legacy path: play-phase plugin channel (works pre-1.20.2 and on some forks)
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        // Since 1.20.2 the brand arrives during the CONFIGURATION phase and the
        // play-phase messenger never sees it - the whole detector would be silently
        // dead on the project's target (1.21.x). Paper exposes it as
        // PlayerClientBrandEvent; hook that via reflection (no compile-time dep).
        try {
            Class<? extends org.bukkit.event.Event> brandEvent = Class
                    .forName("io.papermc.paper.event.player.PlayerClientBrandEvent")
                    .asSubclass(org.bukkit.event.Event.class);
            java.lang.reflect.Method getPlayer = brandEvent.getMethod("getPlayer");
            java.lang.reflect.Method getBrand = brandEvent.getMethod("getBrand");
            Bukkit.getPluginManager().registerEvent(brandEvent, this,
                    org.bukkit.event.EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            handle((Player) getPlayer.invoke(event), (String) getBrand.invoke(event));
                        } catch (Throwable t) {
                            plugin.debug("ClientBrandEvent handling failed: " + t);
                        }
                    }, plugin);
            plugin.getLogger().info("Client detector: Paper PlayerClientBrandEvent hooked "
                    + "(configuration-phase brands visible).");
        } catch (ClassNotFoundException ignored) {
            plugin.getLogger().warning("Client detector: this build has no PlayerClientBrandEvent and "
                    + "1.20.2+ sends the brand in the configuration phase - client verdicts may "
                    + "never arrive here. Consider Paper, or expect UNKNOWN-only verdicts.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Client detector: brand event hook failed: " + t);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin); // join self-test below
    }

    /**
     * Inactivity self-test (A2): a detector that never receives a single brand
     * must SAY SO instead of silently returning UNKNOWN for everyone.
     */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        joins++;
        if (joins >= 10 && brandsSeen == 0 && !inactivityWarned) {
            inactivityWarned = true;
            plugin.getLogger().warning("Client detection INACTIVE: 10+ joins and not a single "
                    + "minecraft:brand was received on this server build.");
        }
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
        brandsSeen++;
        String lower = normalized.toLowerCase(Locale.ROOT);

        // staff/testers with bypass: record the brand for /ac info, never act on it
        if (player.hasPermission("wepac.bypass")) {
            data.clientVerdict = "BYPASS";
            return;
        }

        // 1) definitive cheat client (the client announced itself by brand;
        //    a legit client has no reason to carry a cheat's brand string).
        //    Brands are still SELF-REPORTED and a modpack could collide with a
        //    list entry, so banning on brand alone is opt-in: by default the
        //    player goes on maximum watch and staff get a loud alert.
        for (String cheat : plugin.config().cheatBrands) {
            if (matchBrand(lower, cheat)) {
                data.clientVerdict = "CHEAT";
                if (plugin.config().clientBanOnBrand) {
                    ban(player, normalized);
                } else {
                    cheatWatch(player, normalized);
                }
                return;
            }
        }
        // 2) explicitly suspicious brands -> watch
        for (String watch : plugin.config().watchBrands) {
            if (matchBrand(lower, watch)) {
                data.clientVerdict = "SUSPICIOUS";
                startWatch(player, normalized);
                return;
            }
        }
        // 3) known legit clients
        for (Map.Entry<String, String> e : plugin.config().knownBrands.entrySet()) {
            if (matchBrand(lower, e.getKey())) {
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
                + " &7— клиент сам объявил себя читерским брендом, баню.");
        String cmd = plugin.config().clientBanCommand
                .replace("%player%", player.getName())
                .replace("%client%", brand);
        // NOTE: deliberately NO labelFromPunishment here - at join time the
        // telemetry is empty, and a "zeros = cheater" sample would contradict
        // the near-zero vectors of honest AFK players and poison the model.
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        plugin.records().addStrike(player, CheckType.CLIENT, true);
    }

    /** Cheat-brand match with ban-on-brand disabled: loudest possible watch, no auto-punish. */
    private void cheatWatch(Player player, String brand) {
        RecordManager.Status current = plugin.records().status(player.getUniqueId());
        if (current == RecordManager.Status.CLEAN) {
            plugin.records().setStatus(player, RecordManager.Status.WATCH, "client:" + brand);
        }
        plugin.alerts().broadcastRaw("&4&l[КЛИЕНТ]&r &c" + player.getName()
                + " &7зашёл с бренда чит-клиента &c" + brand
                + " &7— автобан по бренду выключен (&fclient-detect.ban-on-brand: true&7), "
                + "включена максимальная слежка. Бренд самодекларируется: "
                + "проверьте поведение (&f/ac info&7, &f/ac record&7) до ручного бана.");
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

    /**
     * Collision-safe brand-list matching on the lowercased brand.
     * needle >= 8 chars: plain substring (distinctive enough);
     * needle 6-7 chars:  matches the prefix of a whole token;
     * needle <= 5 chars: exact whole-token equality only.
     * Tokens are runs of [a-z0-9]; anything else is a separator.
     */
    public static boolean matchBrand(String lowerBrand, String needle) {
        if (needle.isEmpty()) return false;
        if (needle.length() >= 8) return lowerBrand.contains(needle);
        int i = 0;
        int n = lowerBrand.length();
        while (i < n) {
            while (i < n && !isTokenChar(lowerBrand.charAt(i))) i++;
            int start = i;
            while (i < n && isTokenChar(lowerBrand.charAt(i))) i++;
            int len = i - start;
            if (len == 0) break;
            if (needle.length() <= 5) {
                if (len == needle.length()
                        && lowerBrand.regionMatches(start, needle, 0, needle.length())) {
                    return true;
                }
            } else if (len >= needle.length()
                    && lowerBrand.regionMatches(start, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** Payload is a varint-prefixed UTF-8 string (network format). */
    public static String readBrand(byte[] data) {
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
