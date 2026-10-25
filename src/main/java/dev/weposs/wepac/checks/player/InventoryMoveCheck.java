package dev.weposs.wepac.checks.player;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Locale;

/**
 * InventoryMove / GuiMove (CatLean KeyboardInputMixin, Toshiba InventoryMove,
 * half of the RU clients): in vanilla the mouse is captured by the open GUI, so
 * the camera CANNOT rotate while a container is open. Any rotation delta above
 * a small tolerance while a real inventory is open is the client freeing the
 * mouse. Buffers make a single glitch impossible to flag.
 *
 * GUI state comes from InventoryOpen/CloseEvent tracking (PlayerData.guiOpen),
 * NOT player.getOpenInventory() - InventoryView became an interface on Paper
 * 1.21+, and a plugin compiled against the older class would crash calling it.
 */
public final class InventoryMoveCheck extends Check {

    public InventoryMoveCheck(WepAC plugin) {
        super(plugin, CheckType.INVENTORYMOVE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        PlayerData data = plugin.data().get(player);
        if (!data.guiOpen) return; // no container GUI open

        double dYaw = Math.abs(wrapDegrees(event.getTo().getYaw() - event.getFrom().getYaw()));
        double dPitch = Math.abs(event.getTo().getPitch() - event.getFrom().getPitch());
        double minRotation = plugin.config().optDouble(CheckType.INVENTORYMOVE, "min-rotation", 2.0);

        if (dYaw > minRotation || dPitch > minRotation) {
            data.invMoveBuffer++;
            int needed = plugin.config().optInt(CheckType.INVENTORYMOVE, "buffer", 5);
            if (data.invMoveBuffer >= needed) {
                flag(player, data, String.format(Locale.ROOT,
                        "rotated %.1f/%.1f deg with %s open (mouse is captured in vanilla)",
                        dYaw, dPitch, data.guiTypeName.isEmpty() ? "a GUI" : data.guiTypeName), 2);
                data.invMoveBuffer = 0;
            }
        } else {
            data.invMoveBuffer = Math.max(0, data.invMoveBuffer - 1);
        }
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}
