
package dev.minecraftparcel;

import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ParcelNetwork {

    private static final int MAX_REQUEST_BYTES = 28_000;

    private static final Map<String, Consumer<ParcelPayloads.InventoryResult>>
            PENDING = new ConcurrentHashMap<>();

    private ParcelNetwork() {}

    public static void send(
            String operation,
            String data,
            Consumer<ParcelPayloads.InventoryResult> callback
    ) {
        if (data.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            callback.accept(new ParcelPayloads.InventoryResult(
                    "",
                    false,
                    "Die Inventaranfrage ist zu groß."
            ));
            return;
        }

        String requestId = UUID.randomUUID().toString();

        PENDING.put(requestId, callback);

        try {
            ClientPacketDistributor.sendToServer(
                    new ParcelPayloads.InventoryRequest(
                            requestId,
                            operation,
                            data
                    )
            );
        } catch (RuntimeException exception) {
            PENDING.remove(requestId);

            callback.accept(new ParcelPayloads.InventoryResult(
                    requestId,
                    false,
                    "Netzwerkfehler: " + exception.getMessage()
            ));
        }
    }

    public static void handleResult(ParcelPayloads.InventoryResult result) {
        Consumer<ParcelPayloads.InventoryResult> callback =
                PENDING.remove(result.requestId());

        if (callback != null) {
            callback.accept(result);
        }
    }
}
