package dev.weposs.wepac.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData get(Player player) {
        return data.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerData(id, player.getName()));
    }

    public PlayerData getIfLoaded(UUID uuid) {
        return data.get(uuid);
    }

    public Collection<PlayerData> all() {
        return data.values();
    }

    public void remove(UUID uuid) {
        data.remove(uuid);
    }

    public void clear() {
        data.clear();
    }
}
