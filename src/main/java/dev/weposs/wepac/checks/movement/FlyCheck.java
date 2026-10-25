package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;

/** Flagging is driven by {@link MovementMonitor} (server-side ground truth). */
public final class FlyCheck extends Check {
    public FlyCheck(WepAC plugin) {
        super(plugin, CheckType.FLY);
    }
}
