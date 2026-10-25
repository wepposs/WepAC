package dev.weposs.wepac.check;

import dev.weposs.wepac.WepAC;

/** Generic check placeholder: flagging is driven by monitors (MovementMonitor / PacketSniffer). */
public final class SimpleCheck extends Check {
    public SimpleCheck(WepAC plugin, CheckType type) {
        super(plugin, type);
    }
}
