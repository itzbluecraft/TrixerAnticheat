package dev.trixer.anticheat;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;

/** Per-player state used by the checks. */
public final class PlayerData {

    public final Map<String, Double> vl = new HashMap<>();

    // Flight
    public Location safe;
    public double lastDeltaY;
    public int airTicks;
    public double flightBuf;
    public int stationaryAir;

    // BoatFlight
    public Location boatSafe;
    public int boatAir;
    public double boatBufA;
    public double boatBufB;

    // Timers (ms)
    public long lastPosMove;
    public long lastTeleport;
    public long lastVelocity;
    public long lastGlide;
    public long lastSpecial;
    public long joinTime;

    public void resetAir() {
        airTicks = 0;
        flightBuf = 0;
        stationaryAir = 0;
        lastDeltaY = 0;
    }
}
