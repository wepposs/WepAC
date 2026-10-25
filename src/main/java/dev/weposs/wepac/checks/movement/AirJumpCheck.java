package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;

/** Double/air jump detection. Flagging is driven by {@link MovementMonitor}. */
public final class AirJumpCheck extends Check {
    public AirJumpCheck(WepAC plugin) {
        super(plugin, CheckType.AIRJUMP);
    }
}
