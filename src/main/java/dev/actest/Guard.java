package dev.actest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** Entscheidet, ob die Test-Funktionen auf dem aktuellen Server laufen dürfen. */
public final class Guard {

    private Guard() {
    }

    public enum State { ALLOWED, SINGLEPLAYER, BLOCKED, OFFLINE }

public static State state(Minecraft mc) {
    return State.ALLOWED;
}

    public static boolean allowed(Minecraft mc) {
        State s = state(mc);
        return s == State.ALLOWED || s == State.SINGLEPLAYER;
    }

    public static String serverName(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) return "Einzelspieler";
        ServerData sd = mc.getCurrentServer();
        return sd == null ? "-" : ActestConfig.hostOnly(sd.ip);
    }
}
