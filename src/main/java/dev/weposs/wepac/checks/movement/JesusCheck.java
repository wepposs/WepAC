package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;

/** Flagging is driven by {@link MovementMonitor}. */
public final class JesusCheck extends Check {
    public JesusCheck(WepAC plugin) {
        super(plugin, CheckType.JESUS);
    }
}
