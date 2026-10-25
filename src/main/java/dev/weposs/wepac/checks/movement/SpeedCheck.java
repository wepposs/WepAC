package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;

/** Flagging is driven by {@link MovementMonitor}. */
public final class SpeedCheck extends Check {
    public SpeedCheck(WepAC plugin) {
        super(plugin, CheckType.SPEED);
    }
}
