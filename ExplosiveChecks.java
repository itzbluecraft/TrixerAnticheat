package dev.trixer.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.Deque;
import java.util.Locale;

/**
 * AutoCrystal (A): crystal broken right after placing, (B): too many breaks per second.
 * CrystalAura (A): too many crystals placed per second, (B): crystal hit from too far,
 *                  not looked at, or through walls.
 * AutoAnchor (A): place -> explode too fast, (B): charge -> explode too fast.
 * AnchorAura (A): anchor used from too far / not looked at, (B): too many anchors placed.
 */
public final class ExplosiveChecks implements Listener {

    private final TrixerAnticheat plugin;

    private boolean autoCrystalOn;
    private long minCrystalDelay;
    private int maxBreaks;
    private double acBuffer;

    private boolean crystalAuraOn;
    private int maxCrystalPlaces;
    private double crystalReach;
    private double crystalAngle;

    private boolean autoAnchorOn;
    private long minAnchorTotal;
    private long minAnchorCharge;
    private double anchorBuffer;

    private boolean anchorAuraOn;
    private int maxAnchorPlaces;
    private double anchorReach;
    private double anchorAngle;

    public ExplosiveChecks(TrixerAnticheat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        var c = plugin.getConfig();
        autoCrystalOn = c.getBoolean("checks.autocrystal.enabled", true);
        minCrystalDelay = c.getLong("checks.autocrystal.min-delay-ms", 50L);
        maxBreaks = c.getInt("checks.autocrystal.max-breaks-per-second", 16);
        acBuffer = c.getDouble("checks.autocrystal.buffer", 3.0);

        crystalAuraOn = c.getBoolean("checks.crystalaura.enabled", true);
        maxCrystalPlaces = c.getInt("checks.crystalaura.max-places-per-second", 10);
        crystalReach = c.getDouble("checks.crystalaura.reach", 4.2);
        crystalAngle = c.getDouble("checks.crystalaura.angle", 70.0);

        autoAnchorOn = c.getBoolean("checks.autoanchor.enabled", true);
        minAnchorTotal = c.getLong("checks.autoanchor.min-total-ms", 200L);
        minAnchorCharge = c.getLong("checks.autoanchor.min-charge-ms", 70L);
        anchorBuffer = c.getDouble("checks.autoanchor.buffer", 2.0);

        anchorAuraOn = c.getBoolean("checks.anchoraura.enabled", true);
        maxAnchorPlaces = c.getInt("checks.anchoraura.max-places", 5);
        anchorReach = c.getDouble("checks.anchoraura.reach", 5.2);
        anchorAngle = c.getDouble("checks.anchoraura.angle", 75.0);
    }

    private static void trim(Deque<Long> q, long now, long window) {
        while (!q.isEmpty() && now - q.peekFirst() > window) q.pollFirst();
    }

    // -------------------------------------------------------------- crystals

    @EventHandler(ignoreCancelled = true)
    public void onCrystalPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || !(e.getEntity() instanceof EnderCrystal) || WorldUtil.ignored(p)) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        d.crystalPlaced.put(e.getEntity().getUniqueId(), now);
        if (d.crystalPlaced.size() > 64) d.crystalPlaced.values().removeIf(t -> now - t > 5000L);

        if (!crystalAuraOn) return;
        d.crystalPlaces.addLast(now);
        trim(d.crystalPlaces, now, 1000L);
        if (d.crystalPlaces.size() > maxCrystalPlaces) {
            int count = d.crystalPlaces.size();
            d.crystalPlaces.clear();
            plugin.flag(p, "CrystalAura", "A", count + " crystals placed in 1s", 1.0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof EnderCrystal crystal)) return;
        if (WorldUtil.ignored(p) || (!autoCrystalOn && !crystalAuraOn)) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        boolean flagged = false;

        if (autoCrystalOn) {
            Long placedAt = d.crystalPlaced.remove(crystal.getUniqueId());
            if (placedAt != null) {
                long delay = now - placedAt;
                if (delay < minCrystalDelay) {
                    d.acBuf += 1.0;
                    if (d.acBuf >= acBuffer) {
                        d.acBuf = 0.0;
                        plugin.flag(p, "AutoCrystal", "A", delay + "ms place->break", 1.0);
                        flagged = true;
                    }
                } else {
                    d.acBuf = Math.max(0.0, d.acBuf - 0.5);
                }
            }

            d.crystalBreaks.addLast(now);
            trim(d.crystalBreaks, now, 1000L);
            if (d.crystalBreaks.size() > maxBreaks) {
                int count = d.crystalBreaks.size();
                d.crystalBreaks.clear();
                plugin.flag(p, "AutoCrystal", "B", count + " breaks in 1s", 1.0);
                flagged = true;
            }
        }

        if (crystalAuraOn) {
            Location eye = p.getEyeLocation();
            BoundingBox box = crystal.getBoundingBox();
            double dist = WorldUtil.distanceTo(eye, box);
            double angle = WorldUtil.angleTo(eye, box.getCenter());
            boolean bad = dist > crystalReach || angle > crystalAngle || !WorldUtil.canSee(eye, box);
            if (bad) {
                d.caBuf += 1.0;
                if (d.caBuf >= 3.0) {
                    d.caBuf = 0.0;
                    String info = String.format(Locale.ROOT, "dist=%.2f angle=%.0f", dist, angle);
                    plugin.flag(p, "CrystalAura", "B", info, 1.0);
                    flagged = true;
                }
            } else {
                d.caBuf = Math.max(0.0, d.caBuf - 0.5);
            }
        }

        if (flagged && plugin.cancelHits()) e.setCancelled(true);
    }

    // --------------------------------------------------------------- anchors

    private static String key(Block b) {
        return b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnchorPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.RESPAWN_ANCHOR) return;
        Player p = e.getPlayer();
        if (WorldUtil.ignored(p) || (!autoAnchorOn && !anchorAuraOn)) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        d.anchors.put(key(e.getBlockPlaced()), new long[]{now, 0L});
        if (d.anchors.size() > 32) d.anchors.values().removeIf(r -> now - r[0] > 15000L);

        if (!anchorAuraOn) return;
        d.anchorPlaces.addLast(now);
        trim(d.anchorPlaces, now, 2000L);
        if (d.anchorPlaces.size() > maxAnchorPlaces) {
            int count = d.anchorPlaces.size();
            d.anchorPlaces.clear();
            plugin.flag(p, "AnchorAura", "B", count + " anchors placed in 2s", 1.0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnchorUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.RESPAWN_ANCHOR) return;

        Player p = e.getPlayer();
        if (WorldUtil.ignored(p) || (!autoAnchorOn && !anchorAuraOn)) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        // ---- AnchorAura (A): reach / aim
        if (anchorAuraOn) {
            Location eye = p.getEyeLocation();
            BoundingBox box = BoundingBox.of(b);
            double dist = WorldUtil.distanceTo(eye, box);
            double angle = WorldUtil.angleTo(eye, box.getCenter());
            if (dist > anchorReach || angle > anchorAngle) {
                d.anchorAuraBuf += 1.0;
                if (d.anchorAuraBuf >= 2.0) {
                    d.anchorAuraBuf = 0.0;
                    String info = String.format(Locale.ROOT, "dist=%.2f angle=%.0f", dist, angle);
                    plugin.flag(p, "AnchorAura", "A", info, 1.0);
                    if (plugin.cancelHits()) {
                        e.setUseInteractedBlock(Event.Result.DENY);
                        e.setUseItemInHand(Event.Result.DENY);
                    }
                    return;
                }
            } else {
                d.anchorAuraBuf = Math.max(0.0, d.anchorAuraBuf - 0.5);
            }
        }

        if (!autoAnchorOn || !(b.getBlockData() instanceof RespawnAnchor anchor)) return;

        ItemStack hand = e.getItem();
        boolean glowstone = hand != null && hand.getType() == Material.GLOWSTONE;
        int charges = anchor.getCharges();
        boolean charging = glowstone && charges < anchor.getMaximumCharges();
        boolean exploding = !charging && charges > 0 && !b.getWorld().isRespawnAnchorWorks();

        String key = key(b);
        long[] rec = d.anchors.get(key);
        if (rec == null) return;

        if (charging) {
            rec[1] = now;
            return;
        }
        if (!exploding) return;

        d.anchors.remove(key);
        long total = now - rec[0];
        long charge = rec[1] > 0L ? now - rec[1] : -1L;

        String type = null;
        if (charge >= 0L && charge < minAnchorCharge) type = "B";
        else if (charge >= 0L && total < minAnchorTotal) type = "A";

        if (type != null) {
            d.anchorBuf += 1.0;
            if (d.anchorBuf >= anchorBuffer) {
                d.anchorBuf = 0.0;
                String info = "place->explode " + total + "ms, charge->explode " + charge + "ms";
                plugin.flag(p, "AutoAnchor", type, info, 1.0);
            }
        } else {
            d.anchorBuf = Math.max(0.0, d.anchorBuf - 0.5);
        }
    }
}
