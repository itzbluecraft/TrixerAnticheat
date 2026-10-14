package dev.trixer.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * Flight (A): vanilla gravity prediction (hover, fly-up, slow-fall).
 * Flight (B): standing still in mid-air.
 * BoatFlight (A/B): boat rising / not falling while not in water.
 * NoFall (A): spoofed on-ground flag while falling. (B): big fall, no fall damage.
 * Jesus (A): walking on the surface of water / lava.
 */
public final class MovementChecks implements Listener {

    private final TrixerAnticheat plugin;

    private boolean flightOn;
    private double flightBuffer;
    private double tolerance;
    private int stationaryTicks;
    private boolean boatOn;
    private double boatBuffer;
    private boolean noFallOn;
    private double noFallBuffer;
    private double minFall;
    private boolean jesusOn;
    private int jesusLimit;

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
        noFallOn = c.getBoolean("checks.nofall.enabled", true);
        noFallBuffer = c.getDouble("checks.nofall.buffer", 3.0);
        minFall = c.getDouble("checks.nofall.min-fall", 4.5);
        jesusOn = c.getBoolean("checks.jesus.enabled", true);
        jesusLimit = c.getInt("checks.jesus.ticks", 8);
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
        d.pendingFall = 0;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.resetAir();
        d.pendingFall = 0;
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        plugin.data(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        plugin.data(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p) {
            plugin.data(p).lastFallDamage = System.currentTimeMillis();
        }
    }

    // ---------------------------------------------------- player movement

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!flightOn && !noFallOn && !jesusOn) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        // Rotation-only moves are ignored
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        World w = to.getWorld();

        d.lastPosMove = now;
        d.stationaryAir = 0;

        // On the ground: remember a safe spot and reset the air state
        if (WorldUtil.onGround(to, 0.1)) {
            if (noFallOn) landing(p, d, now, to);
            d.safe = to.clone();
            d.airTicks = 0;
            d.fallDist = 0.0;
            d.jesusTicks = 0;
            d.flightBuf = Math.max(0.0, d.flightBuf - 1.0);
            d.noFallBuf = Math.max(0.0, d.noFallBuf - 0.5);
            d.lastDeltaY = dy;
            return;
        }

        // NoFall (A): client says "on ground" while the server sees open air below
        if (noFallOn && p.isOnGround() && dy < -0.05 && !WorldUtil.onGround(from, 0.3)
                && !baseExempt(p, d, now) && !WorldUtil.nearSpecial(w, WorldUtil.boxAt(to))) {
            // Restore the real fall distance so vanilla still deals the fall damage
            p.setFallDistance((float) Math.max(p.getFallDistance(), d.fallDist - dy));
            d.noFallBuf += 1.0;
            if (d.noFallBuf >= noFallBuffer) {
                d.noFallBuf = noFallBuffer / 2.0;
                String info = String.format(Locale.ROOT, "spoofed ground dy=%.3f fall=%.1f", dy, d.fallDist);
                boolean kicked = plugin.flag(p, "NoFall", "A", info, 1.0);
                if (!kicked) setBack(e, d, to);
            }
        }

        boolean exempt = exempt(p, d, now, to);

        // Jesus runs where flight is exempt (liquids)
        if (jesusOn) checkJesus(e, p, d, to, dx, dy, dz, now);

        if (exempt) {
            d.airTicks = 0;
            d.flightBuf = 0.0;
            d.fallDist = 0.0;
            d.lastDeltaY = dy;
            return;
        }

        // Own fall distance tracking (moving up lowers it, like vanilla)
        d.fallDist -= dy;
        if (d.fallDist < 0.0) d.fallDist = 0.0;

        if (!flightOn) {
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
                if (!kicked) setBack(e, d, to);
            }
        } else {
            d.flightBuf = Math.max(0.0, d.flightBuf - 0.25);
        }
    }

    private void setBack(PlayerMoveEvent e, PlayerData d, Location to) {
        if (!plugin.setback() || d.safe == null) return;
        Location back = d.safe.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
        d.resetAir();
    }

    /** NoFall (B) bookkeeping: remember big falls, tick() verifies fall damage happened. */
    private void landing(Player p, PlayerData d, long now, Location to) {
        if (d.fallDist < minFall) return;
        if (baseExempt(p, d, now) || WorldUtil.nearSpecial(to.getWorld(), WorldUtil.boxAt(to))) return;
        if (Boolean.FALSE.equals(to.getWorld().getGameRuleValue(GameRule.FALL_DAMAGE))) return;

        PotionEffect jump = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
        double allowance = jump == null ? 0.0 : jump.getAmplifier() + 1.0;
        if (d.fallDist < minFall + allowance) return;

        d.pendingFall = d.fallDist;
        d.pendingFallTime = now;
    }

    private void checkJesus(PlayerMoveEvent e, Player p, PlayerData d, Location to,
                            double dx, double dy, double dz, long now) {
        if (baseExempt(p, d, now)) {
            d.jesusTicks = 0;
            return;
        }

        World w = to.getWorld();
        int bx = to.getBlockX();
        int bz = to.getBlockZ();
        int by = (int) Math.floor(to.getY() - 0.01);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return;

        Block b = w.getBlockAt(bx, by, bz);
        Material m = b.getType();
        if ((m != Material.WATER && m != Material.LAVA) || !(b.getBlockData() instanceof Levelled lvl)) {
            d.jesusTicks = Math.max(0, d.jesusTicks - 2);
            return;
        }

        int level = lvl.getLevel();
        if (level >= 8 || b.getRelative(BlockFace.UP).isLiquid()) {
            d.jesusTicks = 0;
            return;
        }

        // Height of the liquid surface in this block
        double top = by + (8 - level) / 9.0;
        double y = to.getY();
        double horizontal = Math.hypot(dx, dz);

        if (y >= top - 0.05 && y <= top + 0.25 && horizontal > 0.03 && Math.abs(dy) < 0.025) {
            d.jesusTicks++;
        } else {
            d.jesusTicks = Math.max(0, d.jesusTicks - 2);
        }

        if (d.jesusTicks >= jesusLimit) {
            d.jesusTicks = 0;
            String info = String.format(Locale.ROOT, "on %s surface dy=%.3f speed=%.2f",
                    m.name().toLowerCase(Locale.ROOT), dy, horizontal);
            boolean kicked = plugin.flag(p, "Jesus", "A", info, 1.0);
            if (!kicked) setBack(e, d, to);
        }
    }

    /** Runs every 5 ticks: hover without movement packets + NoFall (B) verification. */
    public void tick() {
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data(p);

            // NoFall (B): landed from a big fall without taking any fall damage
            if (d.pendingFall > 0.0 && now - d.pendingFallTime >= 500) {
                double fall = d.pendingFall;
                d.pendingFall = 0.0;
                if (d.lastFallDamage < d.pendingFallTime - 100) {
                    String info = String.format(Locale.ROOT, "fell %.1f blocks, no damage", fall);
                    plugin.flag(p, "NoFall", "B", info, 2.0);
                }
            }

            if (!flightOn) continue;

            if (now - d.lastPosMove < 150) {
                d.stationaryAir = 0;
                continue;
            }

            Location loc = p.getLocation();
            if (p.getPing() > 400 || WorldUtil.onGround(loc, 0.5) || exempt(p, d, now, loc)) {
                d.stationaryAir = 0;
                continue;
            }

            d.stationaryAir += 5;
            if (d.stationaryAir >= stationaryTicks) {
                d.stationaryAir = 0;
                boolean kicked = plugin.flag(p, "Flight", "B", "stationary in mid-air", 3.0);
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
        if (WorldUtil.nearSpecial(w, box) || WorldUtil.onGroundBox(w, box, 0.5)) {
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

    /** Everything that makes movement checks pointless (except nearby special blocks). */
    private boolean baseExempt(Player p, PlayerData d, long now) {
        if (WorldUtil.ignored(p)) return true;
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

        return p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private boolean exempt(Player p, PlayerData d, long now, Location loc) {
        if (baseExempt(p, d, now)) return true;

        if (WorldUtil.nearSpecial(loc.getWorld(), WorldUtil.boxAt(loc))) {
            d.lastSpecial = now;
            return true;
        }
        return now - d.lastSpecial < 500;
    }
}
