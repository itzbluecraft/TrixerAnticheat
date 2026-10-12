package dev.trixer.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TrixerAnticheat extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();

    private MovementChecks movement;
    private boolean setback;
    private boolean consoleAlerts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        movement = new MovementChecks(this);
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(movement, this);

        // Stationary-in-air check
        Bukkit.getScheduler().runTaskTimer(this, movement::tick, 5L, 5L);

        // VL decay
        long period = Math.max(1L, getConfig().getLong("vl-decay-interval", 30L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::decay, period, period);

        getLogger().info("Trixer Anticheat (AE) enabled");
    }

    @Override
    public void onDisable() {
        players.clear();
    }

    private void loadSettings() {
        reloadConfig();
        setback = getConfig().getBoolean("setback", true);
        consoleAlerts = getConfig().getBoolean("alerts-to-console", true);
        movement.load();
    }

    public PlayerData data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    public boolean setback() {
        return setback;
    }

    private Component msg(String key, String... replace) {
        String text = getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < replace.length; i += 2) {
            text = text.replace(replace[i], replace[i + 1]);
        }
        return LEGACY.deserialize(text);
    }

    /**
     * Adds VL, alerts staff and kicks at max-vl.
     *
     * @return true if the player was kicked
     */
    public boolean flag(Player p, String check, String type, String info, double add) {
        PlayerData d = data(p);
        double vl = d.vl.merge(check, add, Double::sum);
        String vlText = String.format(Locale.ROOT, "%.1f", vl);

        Component alert = msg("alert",
                "%player%", p.getName(),
                "%check%", check,
                "%type%", type,
                "%vl%", vlText,
                "%ping%", String.valueOf(p.getPing()),
                "%info%", info);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("trixer.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                staff.sendMessage(alert);
            }
        }
        if (consoleAlerts) {
            getLogger().info(p.getName() + " failed " + check + " (" + type + ") VL " + vlText + " [" + info + "]");
        }

        double max = getConfig().getDouble("checks." + check.toLowerCase(Locale.ROOT) + ".max-vl", 15.0);
        if (vl >= max) {
            d.vl.put(check, 0.0);
            getLogger().info("Kicked " + p.getName() + " for " + check);
            String kick = getConfig().getString("kick-message", "&cUnfair advantage detected.");
            p.kick(LEGACY.deserialize(kick.replace("%check%", check)));
            return true;
        }
        return false;
    }

    private void decay() {
        double amount = getConfig().getDouble("vl-decay-amount", 1.0);
        for (PlayerData d : players.values()) {
            d.vl.replaceAll((k, v) -> Math.max(0.0, v - amount));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        players.remove(e.getPlayer().getUniqueId());
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!s.hasPermission("trixer.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                loadSettings();
                s.sendMessage(msg("reloaded"));
            }
            case "alerts" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(LEGACY.deserialize("&cPlayers only."));
                    return true;
                }
                if (!p.hasPermission("trixer.alerts")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                if (alertsOff.remove(p.getUniqueId())) {
                    s.sendMessage(msg("alerts-on"));
                } else {
                    alertsOff.add(p.getUniqueId());
                    s.sendMessage(msg("alerts-off"));
                }
            }
            case "vl" -> {
                if (!s.hasPermission("trixer.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) {
                    s.sendMessage(msg("player-not-found"));
                    return true;
                }
                PlayerData d = data(t);
                s.sendMessage(LEGACY.deserialize("&bTrixer &8» &f" + t.getName() + " &7VL: &f"
                        + (d.vl.isEmpty() ? "none" : d.vl.toString())));
            }
            case "reset" -> {
                if (!s.hasPermission("trixer.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) {
                    s.sendMessage(msg("player-not-found"));
                    return true;
                }
                data(t).vl.clear();
                s.sendMessage(LEGACY.deserialize("&bTrixer &8» &7VL reset for &f" + t.getName()));
            }
            default -> s.sendMessage(LEGACY.deserialize(
                    "&bTrixer Anticheat &8(&7AE&8) &7- &f/trixer <reload|alerts|vl|reset>"));
        }
        return true;
    }
}
