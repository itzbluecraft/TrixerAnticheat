package dev.trixer.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Flight (A): vanilla gravity prediction (catches hover, fly-up and slow-fall hacks).
 * Flight (B): standing still in mid-air.
 * BoatFlight (A): boat rising while not in water.
 * BoatFlight (B): boat not falling while not in water.
 */
public final class MovementChecks implements Listener {

    private static final Set<Material> SPECIAL = EnumSet.of(
            Material.COBWEB, Material.BUBBLE_COLUMN, Material.POWDER_SNOW, Material.HONEY_BLOCK,
            Material.SLIME_BLOCK, Material.MOVING_PISTON, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT);

    private final TrixerAnticheat plugin;

    private boolean flightOn;
    private double flightBuffer;
    private double tolerance;
    private int stationaryTicks;
    private boolean boatOn;
    private double boatBuffer;

    public MovementChecks(TrixerAnticheat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        var c = plugin.getConfig();
        flightOn = c.getBoolean("checks.flight.enabled", true);
        flightBuffer = c.getDouble("checks.flight.buffer", 4.0);
        tolerance = c.getDouble("checks.flight.tolerance", 0.03);
        stationaryTicks = c.getInt("checks.flight.stationary-ticks", 40);
        boatOn = c.getBoolean("checks.boatflight.enabled", true);
        boatBuffer = c.getDouble("checks.boatflight.buffer", 6.0);
    }

    // ------------------------------------------------------- state events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.joinTime = System.currentTimeMillis();
        d.safe = e.getPlayer().getLocation().clone();
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.resetAir();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.resetAir();
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        plugin.data(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        plugin.data(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    // ------------------------------------------------------------- flight

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!flightOn) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        // Rotation-only moves are ignored
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        double dy = to.getY() - from.getY();

        d.lastPosMove = now;
        d.stationaryAir = 0;

        // On the ground: remember a safe spot and reset the air state
        if (onGround(to, 0.1)) {
            d.safe = to.clone();
            d.airTicks = 0;
            d.flightBuf = Math.max(0.0, d.flightBuf - 1.0);
            d.lastDeltaY = dy;
            return;
        }

        if (exempt(p, d, now, to)) {
            d.airTicks = 0;
            d.flightBuf = 0.0;
            d.lastDeltaY = dy;
            return;
        }

        d.airTicks++;
        // Vanilla: next vertical speed = (previous - 0.08) * 0.98
        double expected = (d.lastDeltaY - 0.08) * 0.98;
        double excess = dy - expected;
        d.lastDeltaY = dy;

        // The first air tick is the jump / ledge step, so skip it
        if (d.airTicks <= 1) return;

        if (excess > tolerance) {
            d.flightBuf += 1.0;
            if (d.flightBuf >= flightBuffer) {
                d.flightBuf = flightBuffer / 2.0;
                String info = String.format(Locale.ROOT, "dy=%.3f expected=%.3f air=%d", dy, expected, d.airTicks);
                boolean kicked = plugin.flag(p, "Flight", "A", info, 1.0);
                if (!kicked && plugin.setback() && d.safe != null) {
                    Location back = d.safe.clone();
                    back.setYaw(to.getYaw());
                    back.setPitch(to.getPitch());
                    e.setTo(back);
                    d.resetAir();
                }
            }
        } else {
            d.flightBuf = Math.max(0.0, d.flightBuf - 0.25);
        }
    }

    /** Runs every 5 ticks: players hovering without sending any movement. */
    public void tick() {
        if (!flightOn) return;
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data(p);
            if (now - d.lastPosMove < 150) {
                d.stationaryAir = 0;
                continue;
            }

            Location loc = p.getLocation();
            if (p.getPing() > 400 || onGround(loc, 0.5) || exempt(p, d, now, loc)) {
                d.stationaryAir = 0;
                continue;
            }

            d.stationaryAir += 5;
            if (d.stationaryAir >= stationaryTicks) {
                d.stationaryAir = 0;
                boolean kicked = plugin.flag(p, "Flight", "B",
                        "stationary in mid-air", 3.0);
                if (!kicked && plugin.setback() && d.safe != null) {
                    p.teleport(d.safe);
                }
            }
        }
    }

    // ----------------------------------------------------------- boatflight

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!boatOn || !(e.getVehicle() instanceof Boat boat)) return;

        Player found = null;
        for (Entity passenger : boat.getPassengers()) {
            if (passenger instanceof Player pl) {
                found = pl;
                break;
            }
        }
        if (found == null || found.hasPermission("trixer.bypass")) return;
        final Player driver = found;

        PlayerData d = plugin.data(driver);
        long now = System.currentTimeMillis();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getWorld() != to.getWorld()) return;

        if (now - d.lastTeleport < 1500 || now - d.lastVelocity < 1500 || now - d.joinTime < 3000) {
            d.boatAir = 0;
            return;
        }

        World w = to.getWorld();
        BoundingBox box = boat.getBoundingBox();

        // In water / on the ground = fine, remember the spot
        if (nearSpecial(w, box) || onGroundBox(w, box, 0.5)) {
            d.boatSafe = to.clone();
            d.boatAir = 0;
            d.boatBufA = Math.max(0.0, d.boatBufA - 1.0);
            d.boatBufB = Math.max(0.0, d.boatBufB - 1.0);
            return;
        }

        d.boatAir++;
        double dy = to.getY() - from.getY();

        // A: rising while in the air
        if (dy > 0.03) d.boatBufA += 1.0;
        else d.boatBufA = Math.max(0.0, d.boatBufA - 0.5);

        // B: not falling like a boat should
        if (d.boatAir > 8 && dy > -0.02) d.boatBufB += 1.0;
        else d.boatBufB = Math.max(0.0, d.boatBufB - 0.5);

        String type = null;
        if (d.boatBufA >= boatBuffer) type = "A";
        else if (d.boatBufB >= boatBuffer) type = "B";
        if (type == null) return;

        int air = d.boatAir;
        d.boatBufA = 0.0;
        d.boatBufB = 0.0;
        d.boatAir = 0;

        String info = String.format(Locale.ROOT, "dy=%.3f air=%d", dy, air);
        boolean kicked = plugin.flag(driver, "BoatFlight", type, info, 1.0);

        if (!kicked && plugin.setback() && d.boatSafe != null) {
            final Location safe = d.boatSafe.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                boat.eject();
                driver.teleport(safe);
                boat.teleport(safe);
            });
        }
    }

    // -------------------------------------------------------------- helpers

    private boolean exempt(Player p, PlayerData d, long now, Location loc) {
        if (p.hasPermission("trixer.bypass")) return true;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying()) return true;
        if (p.isDead() || p.isSleeping() || p.isInsideVehicle()) return true;

        if (p.isGliding() || p.isRiptiding()) {
            d.lastGlide = now;
            return true;
        }
        if (now - d.lastGlide < 1500 || now - d.lastTeleport < 1500
                || now - d.lastVelocity < 2000 || now - d.joinTime < 3000) {
            return true;
        }

        if (p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            return true;
        }

        if (nearSpecial(loc.getWorld(), boxAt(loc))) {
            d.lastSpecial = now;
            return true;
        }
        return now - d.lastSpecial < 500;
    }

    private BoundingBox boxAt(Location l) {
        return new BoundingBox(l.getX() - 0.3, l.getY(), l.getZ() - 0.3,
                l.getX() + 0.3, l.getY() + 1.8, l.getZ() + 0.3);
    }

    /** Is there something solid within {@code down} blocks below the feet? */
    private boolean onGround(Location loc, double down) {
        BoundingBox probe = new BoundingBox(loc.getX() - 0.31, loc.getY() - down, loc.getZ() - 0.31,
                loc.getX() + 0.31, loc.getY(), loc.getZ() + 0.31);
        return touchesSolid(loc.getWorld(), probe);
    }

    private boolean onGroundBox(World w, BoundingBox box, double down) {
        BoundingBox probe = new BoundingBox(box.getMinX(), box.getMinY() - down, box.getMinZ(),
                box.getMaxX(), box.getMinY(), box.getMaxZ());
        return touchesSolid(w, probe);
    }

    private boolean touchesSolid(World w, BoundingBox probe) {
        int x0 = (int) Math.floor(probe.getMinX());
        int x1 = (int) Math.floor(probe.getMaxX());
        int y0 = (int) Math.floor(probe.getMinY());
        int y1 = (int) Math.floor(probe.getMaxY());
        int z0 = (int) Math.floor(probe.getMinZ());
        int z1 = (int) Math.floor(probe.getMaxZ());

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // Never load chunks; treat unloaded as solid to avoid false flags
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return true;
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isPassable()) continue;
                    for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                        if (bb.clone().shift(x, y, z).overlaps(probe)) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Water, climbables, cobwebs, bubble columns, bouncy blocks, etc. around the box. */
    private boolean nearSpecial(World w, BoundingBox box) {
        int x0 = (int) Math.floor(box.getMinX() - 0.2);
        int x1 = (int) Math.floor(box.getMaxX() + 0.2);
        int y0 = (int) Math.floor(box.getMinY() - 0.6);
        int y1 = (int) Math.floor(box.getMaxY() + 0.2);
        int z0 = (int) Math.floor(box.getMinZ() - 0.2);
        int z1 = (int) Math.floor(box.getMaxZ() + 0.2);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return true;
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (b.isLiquid() || SPECIAL.contains(m)
                            || Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m)) {
                        return true;
                    }
                    if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return true;
                }
            }
        }
        return false;
    }
}
