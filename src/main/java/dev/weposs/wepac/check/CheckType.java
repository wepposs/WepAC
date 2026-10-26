package dev.weposs.wepac.check;

/**
 * All detection modules known to WepAC. The id is used as config key
 * (checks.&lt;id-lowercase&gt;) and as %check% placeholder in punishments.
 */
public enum CheckType {
    FLY("Fly", Category.MOVEMENT),
    SPEED("Speed", Category.MOVEMENT),
    JESUS("Jesus", Category.MOVEMENT),
    NOFALL("NoFall", Category.MOVEMENT),
    FASTCLIMB("FastClimb", Category.MOVEMENT),
    WALLCLIMB("WallClimb", Category.MOVEMENT),
    AIRJUMP("AirJump", Category.MOVEMENT),
    PHASE("Phase", Category.MOVEMENT),
    STEP("Step", Category.MOVEMENT),
    NOSLOW("NoSlow", Category.MOVEMENT),
    WATERWALK("WaterWalk", Category.MOVEMENT),
    ELYTRAFLY("ElytraFly", Category.MOVEMENT),
    AIRSTUCK("AirStuck", Category.MOVEMENT),
    LONGJUMP("LongJump", Category.MOVEMENT),
    BOATFLY("BoatFly", Category.MOVEMENT),
    NOWEB("NoWeb", Category.MOVEMENT),
    SILENTROT("SilentRot", Category.MOVEMENT),
    TIMER("Timer", Category.PACKET),
    BLINK("Blink", Category.PACKET),
    BADPACKETS("BadPackets", Category.PACKET),
    PACKETFLY("PacketFly", Category.PACKET),
    REACH("Reach", Category.COMBAT),
    AUTOCLICKER("AutoClicker", Category.COMBAT),
    KILLAURA("KillAura", Category.COMBAT),
    CRITICALS("Criticals", Category.COMBAT),
    VELOCITY("Velocity", Category.COMBAT),
    FASTBOW("FastBow", Category.COMBAT),
    FASTBREAK("FastBreak", Category.BLOCK),
    FASTPLACE("FastPlace", Category.BLOCK),
    SCAFFOLD("Scaffold", Category.BLOCK),
    FASTREGEN("FastRegen", Category.PLAYER),
    FASTEAT("FastEat", Category.PLAYER),
    INVENTORYMOVE("InventoryMove", Category.PLAYER),
    CHESTSTEALER("ChestStealer", Category.PLAYER),
    XRAY("Xray", Category.PLAYER),
    CLIENT("Client", Category.PLAYER),
    AI("AI", Category.AI),
    GROUND_SPOOF("GroundSpoof", Category.MOVEMENT);

    public enum Category { MOVEMENT, COMBAT, BLOCK, PLAYER, PACKET, AI }

    private final String displayName;
    private final Category category;

    CheckType(String displayName, Category category) {
        this.displayName = displayName;
        this.category = category;
    }

    public String displayName() {
        return displayName;
    }

    public Category category() {
        return category;
    }

    public String configKey() {
        return name().toLowerCase(java.util.Locale.ROOT).replace("_", "");
    }
}
