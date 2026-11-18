package dev.minecraftparcel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Isolates access to vanilla's Friends List API. Minecraft 26.2 exposes the
 * friend list through PlayerSocialManager#getFriends(). The reflection layer
 * deliberately keeps the integration point separate while mappings/API
 * signatures are verified against the exact NeoForge workspace.
 *
 * Call this only on the Minecraft client thread. Never treat a display name as
 * an account identifier; delivery recipients must be stable Xbox/Minecraft
 * profile UUIDs returned by the vanilla friend data.
 */
public final class FriendsListBridge {
    private FriendsListBridge() {}

    public static List<Object> readFriendEntries(Object playerSocialManager) {
        if (playerSocialManager == null) return Collections.emptyList();
        try {
            Method method = playerSocialManager.getClass().getMethod("getFriends");
            Object result = method.invoke(playerSocialManager);
            if (result instanceof Iterable<?> iterable) {
                List<Object> friends = new ArrayList<>();
                iterable.forEach(friends::add);
                return List.copyOf(friends);
            }
            MinecraftParcel.LOGGER.warn("Unexpected getFriends() return type: {}",
                    result == null ? "null" : result.getClass().getName());
        } catch (ReflectiveOperationException exception) {
            MinecraftParcel.LOGGER.error("Could not read the 26.2 vanilla Friends List API", exception);
        }
        return Collections.emptyList();
    }
}
