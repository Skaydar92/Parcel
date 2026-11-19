
package dev.minecraftparcel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

@EventBusSubscriber(
        modid = MinecraftParcel.MOD_ID,
        value = Dist.CLIENT
)
public final class ParcelNotificationHandler {

    private static final int CHECK_INTERVAL_TICKS = 20 * 30;

    private static final Path KNOWN_DELIVERIES_FILE =
            Path.of("config", "minecraftparcel", "known-deliveries.json");

    private static final Set<String> knownDeliveryIds = new HashSet<>();

    private static boolean knownIdsLoaded = false;
    private static boolean baselineRequired = false;
    private static boolean requestInFlight = false;
    private static boolean firstCheckStarted = false;

    private static int tickCounter = 0;

    private ParcelNotificationHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        if (!firstCheckStarted) {
            firstCheckStarted = true;
            checkInbox(minecraft);
            return;
        }

        if (requestInFlight) {
            return;
        }

        tickCounter++;

        if (tickCounter >= CHECK_INTERVAL_TICKS) {
            tickCounter = 0;
            checkInbox(minecraft);
        }
    }

    private static void checkInbox(Minecraft minecraft) {
        if (requestInFlight || minecraft.player == null) {
            return;
        }

        requestInFlight = true;

        try {
            ParcelApiClient.createLocal()
                    .getInbox()
                    .whenComplete((deliveries, throwable) ->
                            minecraft.execute(() -> {
                                requestInFlight = false;

                                if (throwable != null) {
                                    Throwable cause =
                                            throwable instanceof CompletionException
                                                    && throwable.getCause() != null
                                                    ? throwable.getCause()
                                                    : throwable;

                                    MinecraftParcel.LOGGER.debug(
                                            "Could not check Parcel inbox: {}",
                                            cause.getMessage()
                                    );
                                    return;
                                }

                                if (minecraft.player == null) {
                                    return;
                                }

                                processDeliveries(minecraft, deliveries);
                            })
                    );
        } catch (IOException | RuntimeException exception) {
            requestInFlight = false;

            MinecraftParcel.LOGGER.debug(
                    "Could not initialize Parcel inbox check: {}",
                    exception.getMessage()
            );
        }
    }

    private static void processDeliveries(
            Minecraft minecraft,
            List<ParcelApiClient.Delivery> deliveries
    ) {
        if (!knownIdsLoaded) {
            loadKnownDeliveryIds();
        }

        if (!knownIdsLoaded) {
            return;
        }

        // Beim allerersten Start vorhandene Pakete registrieren,
        // ohne dafür Benachrichtigungen auszulösen.
        if (baselineRequired) {
            for (ParcelApiClient.Delivery delivery : deliveries) {
                String id = delivery.id();

                if (id != null && !id.isBlank()) {
                    knownDeliveryIds.add(id);
                }
            }

            baselineRequired = false;
            saveKnownDeliveryIds();
            return;
        }

        int newDeliveries = 0;

        for (ParcelApiClient.Delivery delivery : deliveries) {
            String id = delivery.id();

            if (id == null || id.isBlank()) {
                continue;
            }

            if (knownDeliveryIds.add(id)) {
                newDeliveries++;
            }
        }

        if (newDeliveries > 0) {
            saveKnownDeliveryIds();

            String message = newDeliveries == 1
                    ? "Du hast ein neues Paket erhalten!"
                    : "Du hast " + newDeliveries + " neue Pakete erhalten!";

            minecraft.gui.hud.getChat().addClientSystemMessage(
                    Component.literal("[Parcel] " + message)
            );
        }
    }

    private static void loadKnownDeliveryIds() {
        Minecraft minecraft = Minecraft.getInstance();

        Path file = minecraft.gameDirectory.toPath()
                .resolve(KNOWN_DELIVERIES_FILE);

        if (!Files.exists(file)) {
            baselineRequired = true;
            knownIdsLoaded = true;
            return;
        }

        try {
            JsonElement root = JsonParser.parseString(
                    Files.readString(file)
            );

            if (!root.isJsonArray()) {
                throw new IOException(
                        "Known deliveries file is not a JSON array."
                );
            }

            JsonArray array = root.getAsJsonArray();

            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    String id = element.getAsString();

                    if (!id.isBlank()) {
                        knownDeliveryIds.add(id);
                    }
                }
            }

            knownIdsLoaded = true;

        } catch (IOException | RuntimeException exception) {
            MinecraftParcel.LOGGER.warn(
                    "Could not load known Parcel deliveries. "
                            + "Creating a new baseline.",
                    exception
            );

            knownDeliveryIds.clear();
            baselineRequired = true;
            knownIdsLoaded = true;
        }
    }

    private static void saveKnownDeliveryIds() {
        Minecraft minecraft = Minecraft.getInstance();

        Path file = minecraft.gameDirectory.toPath()
                .resolve(KNOWN_DELIVERIES_FILE);

        try {
            Files.createDirectories(file.getParent());

            JsonArray array = new JsonArray();

            for (String id : knownDeliveryIds) {
                array.add(id);
            }

            Files.writeString(file, array.toString());

        } catch (IOException exception) {
            MinecraftParcel.LOGGER.warn(
                    "Could not save known Parcel deliveries.",
                    exception
            );
        }
    }
}
