package dev.minecraftparcel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
public final class ParcelServerHandler {

    private static final Set<String> RECEIVED_DELIVERIES = new HashSet<>();

    private ParcelServerHandler() {
    }

public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");

    // Client -> Server
    registrar.playToServer(
            ParcelPayloads.InventoryRequest.TYPE,
            ParcelPayloads.InventoryRequest.STREAM_CODEC,
            ParcelServerHandler::handle
    );

    // Server -> Client
    registrar.playToClient(
            ParcelPayloads.InventoryResult.TYPE,
            ParcelPayloads.InventoryResult.STREAM_CODEC
    );
}

    private static void handle(
            ParcelPayloads.InventoryRequest request,
            IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        String requestId = request.requestId();

        try {
            JsonElement parsed = JsonParser.parseString(request.data());

            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Ungültige Inventardaten.");
            }

            JsonObject data = parsed.getAsJsonObject();

            String message;

            switch (request.operation()) {
                case "REMOVE" -> {
                    removeItems(player, data);
                    message = "Items erfolgreich aus dem Inventar entfernt.";
                }

                case "ADD" -> {
                    addItems(player, data);
                    message = "Items erfolgreich zum Inventar hinzugefügt.";
                }

                default -> throw new IllegalArgumentException(
                        "Unbekannte Inventaroperation: " + request.operation()
                );
            }

            player.inventoryMenu.broadcastChanges();

            PacketDistributor.sendToPlayer(
                    player,
                    new ParcelPayloads.InventoryResult(requestId, true, message)
            );

        } catch (Exception exception) {
            MinecraftParcel.LOGGER.error(
                    "Inventory request {} failed",
                    requestId,
                    exception
            );

            PacketDistributor.sendToPlayer(
                    player,
                    new ParcelPayloads.InventoryResult(
                            requestId,
                            false,
                            exception.getMessage() != null
                                    ? exception.getMessage()
                                    : "Unbekannter Inventarfehler."
                    )
            );
        }
    }

    private static void removeItems(ServerPlayer player, JsonObject data) {
        if (!data.has("items") || !data.get("items").isJsonArray()) {
            throw new IllegalArgumentException("Keine gültige Itemliste angegeben.");
        }

        JsonArray removals = data.getAsJsonArray("items");

        if (removals.isEmpty()) {
            throw new IllegalArgumentException("Die Itemliste ist leer.");
        }

        Inventory inventory = player.getInventory();

        Map<Integer, Integer> totalCounts = new HashMap<>();
        Map<Integer, ItemStack> expectedStacks = new HashMap<>();

        for (JsonElement element : removals) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Ungültiger Inventareintrag.");
            }

            JsonObject removal = element.getAsJsonObject();

            int slot = removal.get("slot").getAsInt();
            int count = removal.get("count").getAsInt();

            if (slot < 0 || slot >= 36) {
                throw new IllegalArgumentException("Ungültiger Inventarslot: " + slot);
            }

            if (count <= 0) {
                throw new IllegalArgumentException("Ungültige Itemanzahl.");
            }

            if (!removal.has("stack")) {
                throw new IllegalArgumentException("Stack-Daten fehlen.");
            }

            ItemStack expected = ItemStack.CODEC.parse(
                    player.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                    removal.get("stack")
            ).getOrThrow();

            if (expected.isEmpty()) {
                throw new IllegalArgumentException("Ungültiger Itemstack.");
            }

            ItemStack previousExpected = expectedStacks.putIfAbsent(slot, expected);

            if (previousExpected != null
                    && !ItemStack.isSameItemSameComponents(previousExpected, expected)) {
                throw new IllegalArgumentException(
                        "Widersprüchliche Itemdaten für Inventarslot " + slot
                );
            }

            totalCounts.merge(slot, count, Integer::sum);
        }

        for (Map.Entry<Integer, Integer> entry : totalCounts.entrySet()) {
            int slot = entry.getKey();
            int requiredCount = entry.getValue();

            ItemStack current = inventory.getItem(slot);
            ItemStack expected = expectedStacks.get(slot);

            if (current.isEmpty() || current.getCount() < requiredCount) {
                throw new IllegalArgumentException(
                        "Nicht genügend Items in Inventarslot " + slot
                );
            }

            if (!ItemStack.isSameItemSameComponents(current, expected)) {
                throw new IllegalArgumentException(
                        "Item im Inventarslot " + slot + " wurde verändert."
                );
            }
        }

        for (Map.Entry<Integer, Integer> entry : totalCounts.entrySet()) {
            inventory.removeItem(entry.getKey(), entry.getValue());
        }
    }

    private static void addItems(ServerPlayer player, JsonObject data) {
        if (!data.has("deliveryId")
                || !data.get("deliveryId").isJsonPrimitive()) {
            throw new IllegalArgumentException("Ungültige Delivery-ID.");
        }

        String deliveryId = data.get("deliveryId").getAsString();

        if (deliveryId.isBlank() || deliveryId.length() > 128) {
            throw new IllegalArgumentException("Ungültige Delivery-ID.");
        }

        String receiptId = player.getUUID() + ":" + deliveryId;

        if (RECEIVED_DELIVERIES.contains(receiptId)) {
            return;
        }

        if (!data.has("items") || !data.get("items").isJsonArray()) {
            throw new IllegalArgumentException("Keine gültige Itemliste angegeben.");
        }

        JsonArray items = data.getAsJsonArray("items");

        if (items.isEmpty()) {
            throw new IllegalArgumentException("Die Itemliste ist leer.");
        }

        var serializationContext = player.registryAccess()
                .createSerializationContext(JsonOps.INSTANCE);

        List<ItemStack> stacks = new ArrayList<>();

        for (JsonElement element : items) {
            ItemStack stack = ItemStack.CODEC
                    .parse(serializationContext, element)
                    .getOrThrow();

            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }

        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("Keine gültigen Items vorhanden.");
        }

        Inventory inventory = player.getInventory();

        if (!canFitAll(inventory, stacks)) {
            throw new IllegalArgumentException(
                    "Nicht genügend Platz im Inventar."
            );
        }

        for (ItemStack stack : stacks) {
            inventory.add(stack.copy());
        }

        RECEIVED_DELIVERIES.add(receiptId);
    }

    private static boolean canFitAll(
            Inventory inventory,
            List<ItemStack> stacks
    ) {
        ItemStack[] simulated = new ItemStack[inventory.getContainerSize()];

        for (int i = 0; i < simulated.length; i++) {
            simulated[i] = inventory.getItem(i).copy();
        }

        for (ItemStack original : stacks) {
            ItemStack remaining = original.copy();

            for (int i = 0; i < simulated.length && !remaining.isEmpty(); i++) {
                ItemStack existing = simulated[i];

                if (!existing.isEmpty()
                        && ItemStack.isSameItemSameComponents(existing, remaining)) {

                    int space = existing.getMaxStackSize() - existing.getCount();
                    int move = Math.min(space, remaining.getCount());

                    existing.grow(move);
                    remaining.shrink(move);
                }
            }

            for (int i = 0; i < simulated.length && !remaining.isEmpty(); i++) {
                if (simulated[i].isEmpty()) {
                    int move = Math.min(
                            remaining.getCount(),
                            remaining.getMaxStackSize()
                    );

                    simulated[i] = remaining.copyWithCount(move);
                    remaining.shrink(move);
                }
            }

            if (!remaining.isEmpty()) {
                return false;
            }
        }

        return true;
    }
}