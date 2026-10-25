package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;

/**
 * Spider / WallClimb detection. Flagging is driven by {@link MovementMonitor}:
 * a player cannot crawl up a solid wall in vanilla physics.
 */
public final class WallClimbCheck extends Check {
    public WallClimbCheck(WepAC plugin) {
        super(plugin, CheckType.WALLCLIMB);
    }
}
