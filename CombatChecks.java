package dev.trixer.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;

/**
 * AutoTotem (A): totem re-equipped faster than a human can react after a pop.
 * AntiKnockback (A): player barely moves after taking knockback.
 * Reach (A): hitting from too far away.
 * KillAura (A) not looking at the target, (B) multiple targets at once, (C) hitting through walls.
 */
public final class CombatChecks implements Listener {

    private final TrixerAnticheat plugin;

    private boolean totemOn;
    private long minReaction;
    private double totemBuffer;

    private boolean akbOn;
    private double akbRatio;
    private double akbBuffer;

    private boolean reachOn;
    private double baseRange;
    private double reachTolerance;
    private double reachBuffer;

    private boolean auraOn;
    private double auraAngle;
    private int maxTargets;
    private double auraBuffer;

    public CombatChecks(TrixerAnticheat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        var c = plugin.getConfig();
        totemOn = c.getBoolean("checks.autototem.enabled", true);
        minReaction = c.getLong("checks.autototem.min-reaction-ms", 80L);
        totemBuffer = c.getDouble("checks.autototem.buffer", 2.0);

        akbOn = c.getBoolean("checks.antiknockback.enabled", true);
        akbRatio = c.getDouble("checks.antiknockback.ratio", 0.4);
        akbBuffer = c.getDouble("checks.antiknockback.buffer", 3.0);

        reachOn = c.getBoolean("checks.reach.enabled", true);
        baseRange = c.getDouble("checks.reach.base-range", 3.0);
        reachTolerance = c.getDouble("checks.reach.tolerance", 0.35);
        reachBuffer = c.getDouble("checks.reach.buffer", 3.0);

        auraOn = c.getBoolean("checks.killaura.enabled", true);
        auraAngle = c.getDouble("checks.killaura.angle", 70.0);
        maxTargets = c.getInt("checks.killaura.max-targets", 4);
        auraBuffer = c.getDouble("checks.killaura.buffer", 3.0);
    }

    // ------------------------------------------------------------ AutoTotem

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) {
            plugin.data(p).lastPop = System.currentTimeMillis();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!totemOn) return;
        if (isTotem(e.getOffHandItem())) checkTotem(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!totemOn || !(e.getWhoClicked() instanceof Player p)) return;
        if (isTotem(e.getCurrentItem()) || isTotem(e.getCursor())) checkTotem(p);
    }

    private boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }

    private void checkTotem(Player p) {
        if (WorldUtil.ignored(p)) return;
        PlayerData d = plugin.data(p);
        if (d.lastPop == 0L) return;

        long dt = System.currentTimeMillis() - d.lastPop;
        if (dt > 1000L) return;

        // A reacting client answers the pop packet right away, so the ping is not human reaction time
        long reaction = dt - Math.max(0, p.getPing());
        if (reaction < minReaction) {
            d.totemBuf += 1.0;
            if (d.totemBuf >= totemBuffer) {
                d.totemBuf = 0.0;
                plugin.flag(p, "AutoTotem", "A",
                        "re-equip " + dt + "ms after pop (ping " + p.getPing() + ")", 1.0);
            }
        } else {
            d.totemBuf = Math.max(0.0, d.totemBuf - 0.5);
        }
    }

    // -------------------------------------------------------- AntiKnockback

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(PlayerVelocityEvent e) {
        if (!akbOn) return;
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d.akbBusy) return;

        Vector v = e.getVelocity();
        double hv = Math.hypot(v.getX(), v.getZ());
        if (hv < 0.25) return;
        if (akbExempt(p)) return;

        Vector dir = new Vector(v.getX(), 0.0, v.getZ()).normalize();
        Location start = p.getLocation().clone();
        if (blockedAhead(start, dir)) return;

        d.akbBusy = true;
        int total = Math.min(12, 4 + (int) Math.ceil(Math.max(0, p.getPing()) / 50.0));
        new KnockbackTask(p, d, start, dir, hv, total).runTaskTimer(plugin, 1L, 1L);
    }

    private boolean akbExempt(Player p) {
        if (WorldUtil.ignored(p) || p.isFlying() || p.getAllowFlight() || p.isGliding()
                || p.isInsideVehicle() || p.isDead() || p.isSneaking() || p.isBlocking()
                || p.isRiptiding()) {
            return true;
        }
        if (p.getPing() > 250) return true;

        // Netherite armor reduces knockback
        for (ItemStack armor : p.getInventory().getArmorContents()) {
            if (armor != null && armor.getType().name().startsWith("NETHERITE_")) return true;
        }
        return WorldUtil.nearSpecial(p.getWorld(), WorldUtil.boxAt(p.getLocation()));
    }

    /** A wall right behind the player legitimately stops the knockback. */
    private boolean blockedAhead(Location start, Vector dir) {
        BoundingBox box = WorldUtil.boxAt(start).shift(dir.getX() * 0.8, 0.05, dir.getZ() * 0.8);
        return WorldUtil.touchesSolid(start.getWorld(), box);
    }

    private final class KnockbackTask extends BukkitRunnable {
        private final Player p;
        private final PlayerData d;
        private final Location start;
        private final Vector dir;
        private final double hv;
        private final long teleportMark;
        private final int total;
        private int ticks;
        private double best = -10.0;

        KnockbackTask(Player p, PlayerData d, Location start, Vector dir, double hv, int total) {
            this.p = p;
            this.d = d;
            this.start = start;
            this.dir = dir;
            this.hv = hv;
            this.total = total;
            this.teleportMark = d.lastTeleport;
        }

        @Override
        public void run() {
            if (!p.isOnline() || p.isDead() || p.getWorld() != start.getWorld()
                    || d.lastTeleport != teleportMark) {
                d.akbBusy = false;
                cancel();
                return;
            }

            Location now = p.getLocation();
            double along = (now.getX() - start.getX()) * dir.getX()
                    + (now.getZ() - start.getZ()) * dir.getZ();
            best = Math.max(best, along);

            ticks++;
            if (ticks < total) return;

            d.akbBusy = false;
            cancel();

            if (p.getPing() > 250) return;

            double required = hv * akbRatio;
            if (best < required) {
                d.akbBuf += 1.0;
                if (d.akbBuf >= akbBuffer) {
                    d.akbBuf = 0.0;
                    String info = String.format(Locale.ROOT, "moved=%.2f expected=%.2f", best, hv);
                    plugin.flag(p, "AntiKnockback", "A", info, 1.0);
                }
            } else {
                d.akbBuf = Math.max(0.0, d.akbBuf - 0.5);
            }
        }
    }

    // ------------------------------------------------------ Reach + KillAura

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity target)
                || target.getUniqueId().equals(p.getUniqueId())) return;
        if (WorldUtil.ignored(p) || (!reachOn && !auraOn)) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        Location eye = p.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        boolean flagged = false;

        // ---- Reach
        if (reachOn) {
            double dist = WorldUtil.distanceTo(eye, box);
            double allowed = baseRange + reachTolerance + Math.min(Math.max(0, p.getPing()), 250) / 50.0 * 0.15;
            if (dist > allowed) {
                d.reachBuf += 1.0;
                if (d.reachBuf >= reachBuffer) {
                    d.reachBuf = 0.0;
                    String info = String.format(Locale.ROOT, "dist=%.2f allowed=%.2f", dist, allowed);
                    plugin.flag(p, "Reach", "A", info, 1.0);
                    flagged = true;
                }
            } else {
                d.reachBuf = Math.max(0.0, d.reachBuf - 0.5);
            }
        }

        // ---- KillAura
        if (auraOn) {
            // A: not even looking at the target
            double angle = WorldUtil.angleTo(eye, box.getCenter());
            if (angle > auraAngle) {
                d.auraBufA += 1.0;
                if (d.auraBufA >= auraBuffer) {
                    d.auraBufA = 0.0;
                    plugin.flag(p, "KillAura", "A", String.format(Locale.ROOT, "angle=%.0f", angle), 1.0);
                    flagged = true;
                }
            } else {
                d.auraBufA = Math.max(0.0, d.auraBufA - 0.5);
            }

            // B: several different targets at once
            UUID id = target.getUniqueId();
            d.recentTargets.put(id, now);
            d.recentTargets.values().removeIf(t -> now - t > 700L);
            boolean multi = d.recentTargets.size() >= maxTargets
                    || (d.lastTarget != null && !d.lastTarget.equals(id) && now - d.lastAttackTime < 35L);
            d.lastTarget = id;
            d.lastAttackTime = now;
            if (multi) {
                d.auraBufB += 1.0;
                if (d.auraBufB >= 2.0) {
                    d.auraBufB = 0.0;
                    plugin.flag(p, "KillAura", "B", d.recentTargets.size() + " targets in 0.7s", 1.0);
                    flagged = true;
                }
            } else {
                d.auraBufB = Math.max(0.0, d.auraBufB - 0.25);
            }

            // C: hitting through walls
            if (!WorldUtil.canSee(eye, box)) {
                d.auraBufC += 1.0;
                if (d.auraBufC >= auraBuffer) {
                    d.auraBufC = 0.0;
                    plugin.flag(p, "KillAura", "C", "hit through blocks", 1.0);
                    flagged = true;
                }
            } else {
                d.auraBufC = Math.max(0.0, d.auraBufC - 0.5);
            }
        }

        if (flagged && plugin.cancelHits()) e.setCancelled(true);
    }
}
