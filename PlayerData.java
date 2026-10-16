package dev.trixer.anticheat;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player state used by the checks. */
public final class PlayerData {

    public final Map<String, Double> vl = new HashMap<>();

    // Flight / NoFall / Jesus
    public Location safe;
    public double lastDeltaY;
    public int airTicks;
    public double flightBuf;
    public int stationaryAir;
    public double fallDist;
    public double pendingFall;
    public long pendingFallTime;
    public long lastFallDamage;
    public double noFallBuf;
    public int jesusTicks;

    // BoatFlight
    public Location boatSafe;
    public int boatAir;
    public double boatBufA;
    public double boatBufB;

    // AutoTotem / AntiKnockback
    public long lastPop;
    public double totemBuf;
    public double akbBuf;
    public boolean akbBusy;

    // Reach / KillAura
    public double reachBuf;
    public double auraBufA;
    public double auraBufB;
    public double auraBufC;
    public UUID lastTarget;
    public long lastAttackTime;
    public final Map<UUID, Long> recentTargets = new HashMap<>();

    // Crystals / anchors
    public final Map<UUID, Long> crystalPlaced = new HashMap<>();
    public final Deque<Long> crystalPlaces = new ArrayDeque<>();
    public final Deque<Long> crystalBreaks = new ArrayDeque<>();
    public final Deque<Long> anchorPlaces = new ArrayDeque<>();
    /** anchor "x:y:z" -> {placeTime, chargeTime} */
    public final Map<String, long[]> anchors = new HashMap<>();
    public double acBuf;
    public double caBuf;
    public double anchorBuf;
    public double anchorAuraBuf;

    // Timers (ms)
    public long lastPosMove;
    public long lastTeleport;
    public long lastVelocity;
    public long lastGlide;
    public long lastSpecial;
    /** Last wind charge / wind burst / explosion launch or mace hit */
    public long lastBoost;
    public long joinTime;

    public void resetAir() {
        airTicks = 0;
        flightBuf = 0;
        stationaryAir = 0;
        lastDeltaY = 0;
        fallDist = 0;
        jesusTicks = 0;
    }
}
