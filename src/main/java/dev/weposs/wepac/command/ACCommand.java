package dev.weposs.wepac.command;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.ai.FeatureVector;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ACCommand implements CommandExecutor, TabCompleter {

    private final WepAC plugin;

    public ACCommand(WepAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wepac.admin")) {
            sender.sendMessage(WepAC.color("&cNo permission."));
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(WepAC.color(plugin.config().prefix() + "&aConfig reloaded."));
            }
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(WepAC.color("&cPlayers only."));
                    return true;
                }
                boolean on = plugin.alerts().toggle(player);
                player.sendMessage(WepAC.color(plugin.config().prefix()
                        + "&7Alerts &" + (on ? "aenabled" : "cdisabled") + "&7."));
            }
            case "info" -> info(sender, args);
            case "label" -> label(sender, args);
            case "resetvl" -> resetVl(sender, args);
            case "rehabilitate" -> rehabilitate(sender, args);
            case "record" -> record(sender, args);
            case "ai" -> ai(sender, args);
            case "version" -> sender.sendMessage(WepAC.color(
                    plugin.config().prefix() + "&7WepAC &f"
                            + plugin.getDescription().getVersion()
                            + " &7(self-learning AI anti-cheat)"));
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        String p = plugin.config().prefix();
        sender.sendMessage(WepAC.color(p + "&7WepAC commands:"));
        sender.sendMessage(WepAC.color(" &8- &f/ac reload &8- reload config"));
        sender.sendMessage(WepAC.color(" &8- &f/ac alerts &8- toggle staff alerts"));
        sender.sendMessage(WepAC.color(" &8- &f/ac info <player> &8- violations + AI score"));
        sender.sendMessage(WepAC.color(" &8- &f/ac label <player> <cheater|legit> &8- train the AI"));
        sender.sendMessage(WepAC.color(" &8- &f/ac resetvl <player> &8- clear player's violations"));
        sender.sendMessage(WepAC.color(" &8- &f/ac rehabilitate <player> &8- full FP undo (VL + AI samples + record)"));
        sender.sendMessage(WepAC.color(" &8- &f/ac record <player> [watch|wanted|clear] &8- police record"));
        sender.sendMessage(WepAC.color(" &8- &f/ac ai status|save|reset &8- AI management"));
        sender.sendMessage(WepAC.color(" &8- &f/ac version"));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(WepAC.color("&c/ac info <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(WepAC.color("&cPlayer not found."));
            return;
        }
        PlayerData d = plugin.data().get(target);
        String p = plugin.config().prefix();
        sender.sendMessage(WepAC.color(p + "&7Info: &f" + target.getName()));
        sender.sendMessage(WepAC.color(" &8- &7клиент: &f" + d.clientBrand
                + " &8(" + d.clientVerdict + (d.clientName.isEmpty() ? "" : " / " + d.clientName) + ")"));
        sender.sendMessage(WepAC.color(" &8- &7ping: &f" + target.getPing()
                + "ms&8, &7AI: &c" + String.format(Locale.ROOT, "%.1f%%", d.aiProbability * 100)
                + "&8, &7total VL: &f" + d.totalVl()
                + "&8, &7online: &f" + (System.currentTimeMillis() - d.joinTime) / 60000 + "m"
                + (d.legacy18() ? " &8[&e1.8 protocol&8]" : "")
                + (d.bedrock() ? " &8[&eBedrock&8]" : "")));
        if (d.violations.isEmpty()) {
            sender.sendMessage(WepAC.color(" &8- &ano violations"));
        } else {
            for (var e : d.violations.entrySet()) {
                if (e.getValue() <= 0) continue;
                sender.sendMessage(WepAC.color(" &8- &c" + e.getKey().displayName()
                        + "&7: &f" + e.getValue() + " VL"));
            }
        }
        // top learned behavioural stats
        sender.sendMessage(WepAC.color(" &8- &7learned profile (samples " + d.profile.count() + "):"));
        int[] shown = {0, 1, 3, 8};
        for (int i : shown) {
            if (i >= FeatureVector.SIZE) continue;
            sender.sendMessage(WepAC.color("    &8- &7" + FeatureVector.NAMES[i]
                    + String.format(Locale.ROOT, ": mean %.2f, std %.2f",
                    d.profile.mean(i), d.profile.stddev(i))));
        }
    }

    private void label(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(WepAC.color("&c/ac label <player> <cheater|legit>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(WepAC.color("&cPlayer not found."));
            return;
        }
        boolean cheater;
        String v = args[2].toLowerCase(Locale.ROOT);
        if (v.startsWith("ch") || v.equals("1")) cheater = true;
        else if (v.startsWith("l") || v.equals("0")) cheater = false;
        else {
            sender.sendMessage(WepAC.color("&cUse 'cheater' or 'legit'."));
            return;
        }
        plugin.ai().labelByStaff(target, cheater);
        sender.sendMessage(WepAC.color(plugin.config().prefix()
                + "&aLabelled &f" + target.getName() + "&a as " + (cheater ? "CHEATER" : "LEGIT")
                + " &7(AI training sample added)"));
    }

    private void resetVl(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(WepAC.color("&c/ac resetvl <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(WepAC.color("&cPlayer not found."));
            return;
        }
        PlayerData d = plugin.data().get(target);
        d.violations.clear();
        d.speedBuffer = 0;
        d.scaffoldBuffer = 0;
        d.wallClimbBuffer = 0;
        d.airJumpBuffer = 0;
        d.phaseTicks = 0;
        d.hoverTicks = 0;
        d.packetFlyBuffer = 0;
        d.physicsSim.divergence = 0;
        d.auraVotes.clear();
        // A20: a rehabilitation that leaves the FP-derived AI samples and the
        // suspicion score in place is half a rehabilitation
        plugin.ai().purgeCheaterSamplesFrom(target.getUniqueId());
        d.aiProbability = 0.0;
        d.aiProbEma = 0.0;
        d.minedStone = 0;
        d.minedValuable = 0;
        d.xrayFlagCount = 0;
        d.xrayWindowStart = 0L;
        // clear accumulated combat/rotation history so nothing "ripens" after rehab
        d.attackCharges.clear();
        d.attackAirborne.clear();
        d.hitAngles.clear();
        d.hitAnglesPvp.clear();
        d.pitchReversals.clear();
        d.pitchDeltaSamples.clear();
        d.yawDeltaSamples.clear();
        d.clickTimes.clear();
        d.attackTimes.clear();
        d.firedStages.clear();
        d.cooldownLockBuffer = 0;
        d.critRhythmBuffer = 0;
        d.auraPerfectBuffer = 0;
        d.auraGcdBuffer = 0;
        d.auraGcdYawBuffer = 0;
        d.auraPacingBuffer = 0;
        d.auraSwingBuffer = 0;
        d.auraLockBuffer = 0;
        d.auraMultiBuffer = 0;
        d.auraAngleBuffer = 0;
        d.auraRateBuffer = 0;
        d.silentRotBuffer = 0;
        d.antiKbBuffer = 0;
        d.chestStealBuffer = 0;
        d.invMoveBuffer = 0;
        d.critsBuffer = 0;
        d.nukerBuffer = 0;
        d.fastClimbBuffer = 0;
        d.guiAttackBuffer = 0;
        d.cpsBuffer = 0;
        // telemetry history must go too, or detectors "ripen" on the old story
        // right after the rehabilitation
        d.rateAttackTimes.clear();
        d.recentTargets.clear();
        d.invClickTimes.clear();
        d.actionPitchReversals.clear();
        d.actionYawReversals.clear();
        d.speedSamples.clear();
        d.airborneSamples.clear();
        d.risingSamples.clear();
        d.reachSamples.clear();
        d.rotationSamples.clear();
        d.lastAttackAt = 0L;
        // rehabilitation must also clear the persisted copy, or the VL comes
        // back on the next rejoin
        plugin.vlStore().clear(target.getUniqueId());
        sender.sendMessage(WepAC.color(plugin.config().prefix()
                + "&aCleared all violations for &f" + target.getName()));
    }

    /**
     * The official false-positive undo path (A20): everything resetVl does,
     * PLUS the police record is cleared - no residual stricter thresholds,
     * no join announcements, no dossier score from a mistake.
     */
    private void rehabilitate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(WepAC.color("&c/ac rehabilitate <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(WepAC.color("&cPlayer not found."));
            return;
        }
        resetVl(sender, args);
        plugin.records().clear(target.getUniqueId());
        plugin.alerts().broadcastRaw("&a" + target.getName()
                + " &7реабилитирован stafом &f" + sender.getName()
                + "&7: VL, ИИ-сэмплы и учёт сброшены.");
    }

    private void record(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(WepAC.color("&c/ac record <player> [watch|wanted|clear]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(WepAC.color("&cPlayer not found."));
            return;
        }
        String p = plugin.config().prefix();
        if (args.length >= 3) {
            String action = args[2].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "watch" -> plugin.records().setStatus(target,
                        dev.weposs.wepac.record.RecordManager.Status.WATCH, sender.getName());
                case "wanted" -> plugin.records().setStatus(target,
                        dev.weposs.wepac.record.RecordManager.Status.WANTED, sender.getName());
                case "clear" -> {
                    plugin.records().clear(target.getUniqueId());
                    sender.sendMessage(WepAC.color(p + "&aRecord cleared for &f" + target.getName()));
                }
                default -> sender.sendMessage(WepAC.color("&cwatch|wanted|clear"));
            }
            return;
        }

        // dossier
        var rec = plugin.records().get(target.getUniqueId());
        double score = plugin.records().reviewScore(target);
        sender.sendMessage(WepAC.color(p + "&7Досье: &f" + target.getName()));
        sender.sendMessage(WepAC.color(" &8- &7статус: "
                + dev.weposs.wepac.record.RecordManager.statusLabel(rec.status)));
        sender.sendMessage(WepAC.color(" &8- &7киков: &f" + rec.kicks
                + "&8, &7банов: &f" + rec.bans
                + "&8, &7score поведения: &f" + String.format(java.util.Locale.ROOT, "%.0f", score)
                + " &8(порог автобана &f" + String.format(java.util.Locale.ROOT, "%.0f",
                plugin.config().reviewBanScore) + "&8)"));
        if (rec.notes.isEmpty()) {
            sender.sendMessage(WepAC.color(" &8- &aзаписей нет"));
        } else {
            sender.sendMessage(WepAC.color(" &8- &7записи (последние):"));
            int from = Math.max(0, rec.notes.size() - 8);
            for (int i = from; i < rec.notes.size(); i++) {
                sender.sendMessage(WepAC.color("    &8- &f" + rec.notes.get(i)));
            }
        }
    }

    private void ai(CommandSender sender, String[] args) {
        String p = plugin.config().prefix();
        if (!plugin.config().aiEnabled) {
            sender.sendMessage(WepAC.color(p + "&cAI is disabled in config.yml."));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> {
                sender.sendMessage(WepAC.color(p + "&7AI status:"));
                sender.sendMessage(WepAC.color(" &8- &7buffer: &f" + plugin.ai().status()));
                double avg = 0;
                int n = 0;
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    avg += plugin.data().get(pl).aiProbability;
                    n++;
                }
                if (n > 0) {
                    sender.sendMessage(WepAC.color(String.format(Locale.ROOT,
                            " &8- &7avg online probability: &f%.3f &7(%d players)", avg / n, n)));
                }
            }
            case "save" -> {
                // async: the model file write must never block the main thread
                Bukkit.getScheduler().runTaskAsynchronously(plugin, plugin.ai()::save);
                sender.sendMessage(WepAC.color(p + "&aModel save scheduled."));
            }
            case "reset" -> {
                // irreversible: weeks of online learning are gone without confirmation
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    sender.sendMessage(WepAC.color(p + "&cThis IRREVERSIBLY deletes the trained "
                            + "model and the whole training buffer (weeks of learning)."));
                    sender.sendMessage(WepAC.color(p + "&7Repeat with &f/ac ai reset confirm&7 to proceed."));
                } else {
                    plugin.ai().resetModel();
                    sender.sendMessage(WepAC.color(p + "&cModel and training buffer wiped."));
                }
            }
            default -> sender.sendMessage(WepAC.color("&c/ac ai status|save|reset"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("wepac.admin")) return out;
        if (args.length == 1) {
            for (String s : new String[]{"reload", "alerts", "info", "label", "resetvl",
                    "rehabilitate", "record", "ai", "version"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("label")
                || args[0].equalsIgnoreCase("resetvl")
                || args[0].equalsIgnoreCase("rehabilitate")
                || args[0].equalsIgnoreCase("record"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            for (String s : new String[]{"status", "save", "reset"}) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("label")) {
            for (String s : new String[]{"cheater", "legit"}) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("record")) {
            for (String s : new String[]{"watch", "wanted", "clear"}) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }

    /** Referenced so CheckType stays visible for future subcommands. */
    @SuppressWarnings("unused")
    private static CheckType[] allChecks() {
        return CheckType.values();
    }
}
