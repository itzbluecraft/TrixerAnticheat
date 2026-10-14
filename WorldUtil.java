package dev.trixer.anticheat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;

/** Shared world / geometry helpers for all checks. */
public final class WorldUtil {

    private static final Set<Material> SPECIAL = EnumSet.of(
            Material.COBWEB, Material.BUBBLE_COLUMN, Material.POWDER_SNOW, Material.HONEY_BLOCK,
            Material.SLIME_BLOCK, Material.MOVING_PISTON, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT);

    private WorldUtil() {
    }

    /** Creative, spectator and bypass players are never checked. */
    public static boolean ignored(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.hasPermission("trixer.bypass");
    }

    public static BoundingBox boxAt(Location l) {
        return new BoundingBox(l.getX() - 0.3, l.getY(), l.getZ() - 0.3,
                l.getX() + 0.3, l.getY() + 1.8, l.getZ() + 0.3);
    }

    /** Is there something solid within {@code down} blocks below the feet? */
    public static boolean onGround(Location loc, double down) {
        BoundingBox probe = new BoundingBox(loc.getX() - 0.31, loc.getY() - down, loc.getZ() - 0.31,
                loc.getX() + 0.31, loc.getY(), loc.getZ() + 0.31);
        return touchesSolid(loc.getWorld(), probe);
    }

    public static boolean onGroundBox(World w, BoundingBox box, double down) {
        BoundingBox probe = new BoundingBox(box.getMinX(), box.getMinY() - down, box.getMinZ(),
                box.getMaxX(), box.getMinY(), box.getMaxZ());
        return touchesSolid(w, probe);
    }

    public static boolean touchesSolid(World w, BoundingBox probe) {
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
    public static boolean nearSpecial(World w, BoundingBox box) {
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

    /** Distance from the eye to the closest point of the box (0 if inside). */
    public static double distanceTo(Location eye, BoundingBox box) {
        double cx = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Angle (degrees) between where the player looks and the target point. 0 when very close. */
    public static double angleTo(Location eye, Vector target) {
        Vector to = target.clone().subtract(eye.toVector());
        if (to.length() < 1.5) return 0.0;
        return Math.toDegrees(eye.getDirection().angle(to));
    }

    /** Can the eye see at least one of three points (center / top / bottom) of the box? */
    public static boolean canSee(Location eye, BoundingBox box) {
        World w = eye.getWorld();
        double cx = box.getCenterX();
        double cz = box.getCenterZ();
        Vector[] points = {
                box.getCenter(),
                new Vector(cx, box.getMaxY() - 0.1, cz),
                new Vector(cx, box.getMinY() + 0.1, cz)
        };
        Vector origin = eye.toVector();
        for (Vector pt : points) {
            Vector dir = pt.clone().subtract(origin);
            double len = dir.length();
            if (len < 0.5) return true;
            RayTraceResult r = w.rayTraceBlocks(eye, dir.normalize(), len, FluidCollisionMode.NEVER, true);
            if (r == null) return true;
        }
        return false;
    }
}
