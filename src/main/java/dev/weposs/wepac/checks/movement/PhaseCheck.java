package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;

/** Phase/NoClip detection. Flagging is driven by {@link MovementMonitor}. */
public final class PhaseCheck extends Check {
    public PhaseCheck(WepAC plugin) {
        super(plugin, CheckType.PHASE);
    }
}
