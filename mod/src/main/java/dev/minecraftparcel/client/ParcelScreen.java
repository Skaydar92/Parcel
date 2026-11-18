
package dev.minecraftparcel.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.minecraftparcel.ParcelApiClient;
import dev.minecraftparcel.ParcelNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class ParcelScreen extends Screen {

    private enum Tab {
        INBOX,
        SEND,
        FRIENDS
    }

    private static final int INVENTORY_COLUMNS = 9;
    private static final int SLOT_SIZE = 20;
    private static final int SLOT_GAP = 1;

    private static final int GRID_WIDTH =
            INVENTORY_COLUMNS * SLOT_SIZE
                    + (INVENTORY_COLUMNS - 1) * SLOT_GAP;

    private final Screen parent;
    private final Map<Integer, ItemStack> selectedSlots =
            new LinkedHashMap<>();

    private Tab activeTab = Tab.INBOX;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int contentCenter;

    private int inventoryStartX;
    private int inventoryStartY;

    private boolean inboxRequested;
    private boolean inboxLoading;
    private boolean sendInProgress;

    private List<ParcelApiClient.Delivery> deliveries = List.of();

    private String inboxStatus = "Noch nicht geladen.";
    private String selectionStatus = "Wähle Items und gib eine Empfänger-UUID ein.";

    private EditBox recipientField;

    private record SerializedItem(
            int slot,
            int count,
            JsonElement data
    ) {}

    public ParcelScreen(Screen parent) {
        super(Component.literal("Minecraft Parcel"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(500, this.width - 20);
        panelHeight = Math.min(260, this.height - 20);

        panelLeft = (this.width - panelWidth) / 2;
        panelTop = (this.height - panelHeight) / 2;

        contentCenter = panelLeft + 126
                + (panelWidth - 126) / 2;

        inventoryStartX = contentCenter - GRID_WIDTH / 2;
        inventoryStartY = panelTop + 122;

        int sidebarX = panelLeft + 10;

        addRenderableWidget(Button.builder(
                Component.literal("Postfach"),
                button -> changeTab(Tab.INBOX)
        ).bounds(
                sidebarX,
                panelTop + 52,
                106,
                22
        ).build());

        addRenderableWidget(Button.builder(
                Component.literal("Versenden"),
                button -> changeTab(Tab.SEND)
        ).bounds(
                sidebarX,
                panelTop + 80,
                106,
                22
        ).build());

        addRenderableWidget(Button.builder(
                Component.literal("Freunde"),
                button -> changeTab(Tab.FRIENDS)
        ).bounds(
                sidebarX,
                panelTop + 108,
                106,
                22
        ).build());

        if (activeTab == Tab.INBOX) {
            addRenderableWidget(Button.builder(
                    Component.literal("Aktualisieren"),
                    button -> refreshInbox()
            ).bounds(
                    panelLeft + panelWidth - 110,
                    panelTop + 49,
                    98,
                    20
            ).build());

            int rowTop = panelTop + 115;

            for (int i = 0; i < deliveries.size() && i < 4; i++) {
                ParcelApiClient.Delivery delivery = deliveries.get(i);

                addRenderableWidget(Button.builder(
                        Component.literal("Abholen"),
                        button -> claimDelivery(delivery)
                ).bounds(
                        panelLeft + panelWidth - 91,
                        rowTop + i * 38 + 6,
                        72,
                        20
                ).build());
            }

            if (!inboxRequested) {
                refreshInbox();
            }
        }

        if (activeTab == Tab.SEND) {
            recipientField = new EditBox(
                    this.font,
                    contentCenter - 110,
                    panelTop + 84,
                    220,
                    20,
                    Component.literal("Empfänger-UUID")
            );

            recipientField.setMaxLength(36);
            recipientField.setHint(
                    Component.literal("00000000-0000-0000-0000-000000000000")
            );

            addRenderableWidget(recipientField);

            addRenderableWidget(Button.builder(
                    Component.literal("Paket senden"),
                    button -> sendParcel()
            ).bounds(
                    panelLeft + panelWidth - 110,
                    panelTop + 49,
                    98,
                    20
            ).build());
        }

        addRenderableWidget(Button.builder(
                Component.literal("Schließen"),
                button -> onClose()
        ).bounds(
                panelLeft + panelWidth - 94,
                panelTop + panelHeight - 26,
                82,
                18
        ).build());
    }

    private void changeTab(Tab tab) {
        activeTab = tab;
        rebuildWidgets();
    }

    private void refreshInbox() {
        if (inboxLoading) {
            return;
        }

        inboxRequested = true;
        inboxLoading = true;
        inboxStatus = "Verbinde mit localhost:8787 ...";

        try {
            ParcelApiClient client = ParcelApiClient.createLocal();

            client.getInbox().whenComplete((result, error) ->
                    Minecraft.getInstance().execute(() -> {
                        inboxLoading = false;

                        if (error != null) {
                            inboxStatus = "Verbindung fehlgeschlagen: "
                                    + errorMessage(error);
                            return;
                        }

                        deliveries = result;

                        inboxStatus = result.isEmpty()
                                ? "Dein Postfach ist leer."
                                : result.size() + " Paket(e) gefunden.";

                        if (activeTab == Tab.INBOX) {
                            rebuildWidgets();
                        }
                    })
            );

        } catch (IOException exception) {
            inboxLoading = false;
            inboxStatus = "Lokale Konfiguration: "
                    + exception.getMessage();
        }
    }


    private void sendParcel() {
        if (sendInProgress || recipientField == null) {
            return;
        }

        String recipientUuid = recipientField.getValue().trim();

        try {
            UUID.fromString(recipientUuid);
        } catch (IllegalArgumentException exception) {
            selectionStatus = "Ungültige Empfänger-UUID.";
            return;
        }

        List<SerializedItem> serialized;

        try {
            serialized = serializeSelectedItems();
        } catch (Exception exception) {
            selectionStatus = "Serialisierung fehlgeschlagen: "
                    + exception.getMessage();
            return;
        }

        if (serialized.isEmpty()) {
            selectionStatus = "Keine gültigen Items ausgewählt.";
            return;
        }

        JsonArray items = new JsonArray();
        JsonArray removals = new JsonArray();

        for (SerializedItem item : serialized) {
            items.add(item.data());

            JsonObject removal = new JsonObject();
            removal.addProperty("slot", item.slot());
            removal.addProperty("count", item.count());
            removal.add("stack", item.data());

            removals.add(removal);
        }

        JsonObject removeData = new JsonObject();
        removeData.add("items", removals);

        sendInProgress = true;
        selectionStatus = "Server prüft dein Inventar ...";

        String requestId = UUID.randomUUID().toString();

        ParcelNetwork.send(
                "REMOVE",
                removeData.toString(),
                inventoryResult -> {
                    if (!inventoryResult.success()) {
                        sendInProgress = false;
                        selectionStatus = inventoryResult.message();
                        return;
                    }

                    selectionStatus = "Inventar bestätigt. Übertrage Paket ...";

                    try {
                        ParcelApiClient client = ParcelApiClient.createLocal();

                        client.createDelivery(
                                recipientUuid,
                                items,
                                requestId
                        ).whenComplete((result, error) ->
                                Minecraft.getInstance().execute(() -> {
                                    if (error != null) {
                                        refundItems(serialized, requestId,
                                                errorMessage(error));
                                        return;
                                    }

                                    sendInProgress = false;
                                    selectedSlots.clear();

                                    selectionStatus = "Paket erfolgreich versendet. ID: "
                                            + shortUuid(result.deliveryId());
                                })
                        );

                    } catch (IOException exception) {
                        refundItems(
                                serialized,
                                requestId,
                                exception.getMessage()
                        );
                    }
                }
        );
}

private void refundItems(
        List<SerializedItem> serialized,
        String requestId,
        String originalError
) {
    JsonArray items = new JsonArray();

    for (SerializedItem item : serialized) {
        items.add(item.data());
    }

    JsonObject restoreData = new JsonObject();
    restoreData.addProperty("deliveryId", "refund-" + requestId);
    restoreData.add("items", items);

    selectionStatus = "Versand fehlgeschlagen. Stelle Items wieder her ...";

    ParcelNetwork.send(
            "ADD",
            restoreData.toString(),
            result -> {
                sendInProgress = false;

                if (result.success()) {
                    selectionStatus = "Versand fehlgeschlagen. "
                            + "Items wurden wiederhergestellt: "
                            + originalError;
                } else {
                    selectionStatus = "KRITISCH: Versand fehlgeschlagen und "
                            + "Wiederherstellung fehlgeschlagen: "
                            + result.message()
                            + ". Bitte Serverinventar prüfen.";
                }
            }
    );
}


    private void claimDelivery(ParcelApiClient.Delivery delivery) {
        if (inboxLoading) {
            return;
        }

        inboxLoading = true;
        inboxStatus = "Paket wird abgeholt ...";

        try {
            ParcelApiClient client = ParcelApiClient.createLocal();

            client.claimDelivery(delivery.id())
                    .whenComplete((claimed, claimError) ->
                            Minecraft.getInstance().execute(() -> {
                                if (claimError != null) {
                                    inboxLoading = false;
                                    inboxStatus = "Abholung fehlgeschlagen: "
                                            + errorMessage(claimError);
                                    return;
                                }

                                receiveClaimedDelivery(client, claimed);
                            })
                    );

        } catch (IOException exception) {
            inboxLoading = false;
            inboxStatus = "Lokale Konfiguration: "
                    + exception.getMessage();
        }
    }


private void receiveClaimedDelivery(
        ParcelApiClient client,
        ParcelApiClient.ClaimedDelivery claimed
) {
    if (Minecraft.getInstance().player == null) {
        inboxLoading = false;
        inboxStatus = "Kein Spieler verfügbar. "
                + "Die Reservierung läuft nach fünf Minuten ab.";
        return;
    }

    if (claimed.items() == null || claimed.items().isEmpty()) {
        inboxLoading = false;
        inboxStatus = "Das Paket enthält keine Items.";
        return;
    }

    JsonObject addData = new JsonObject();
    addData.addProperty("deliveryId", claimed.deliveryId());
    addData.add("items", claimed.items());

    inboxStatus = "Server prüft den verfügbaren Inventarplatz ...";

    ParcelNetwork.send(
            "ADD",
            addData.toString(),
            result -> {
                if (!result.success()) {
                    inboxLoading = false;
                    inboxStatus = "Abholung fehlgeschlagen: "
                            + result.message();
                    return;
                }

                inboxStatus = "Items eingefügt. Bestätige Paket ...";

                client.completeClaim(
                        claimed.deliveryId(),
                        claimed.claimToken()
                ).whenComplete((ignored, completeError) ->
                        Minecraft.getInstance().execute(() -> {
                            inboxLoading = false;

                            if (completeError != null) {
                                inboxStatus = "Items wurden eingefügt, "
                                        + "aber die Backend-Bestätigung "
                                        + "ist fehlgeschlagen: "
                                        + errorMessage(completeError);
                                return;
                            }

                            inboxStatus = claimed.items().size()
                                    + " Item-Stapel erfolgreich abgeholt.";

                            refreshInbox();
                        })
                );
            }
    );
}


    private static boolean canFitAll(
            Inventory inventory,
            List<ItemStack> incoming
    ) {
        List<ItemStack> simulated = new ArrayList<>(36);

        for (int i = 0; i < 36; i++) {
            simulated.add(inventory.getItem(i).copy());
        }

        for (ItemStack incomingStack : incoming) {
            int remaining = incomingStack.getCount();

            for (ItemStack existing : simulated) {
                if (existing.isEmpty()) {
                    continue;
                }

                if (existing.getItem() != incomingStack.getItem()
                        || !existing.getComponents()
                        .equals(incomingStack.getComponents())) {
                    continue;
                }

                int space = existing.getMaxStackSize()
                        - existing.getCount();

                int moved = Math.min(space, remaining);

                existing.grow(moved);
                remaining -= moved;

                if (remaining <= 0) {
                    break;
                }
            }

            while (remaining > 0) {
                int emptySlot = -1;

                for (int i = 0; i < simulated.size(); i++) {
                    if (simulated.get(i).isEmpty()) {
                        emptySlot = i;
                        break;
                    }
                }

                if (emptySlot < 0) {
                    return false;
                }

                ItemStack placed = incomingStack.copy();
                int moved = Math.min(
                        remaining,
                        placed.getMaxStackSize()
                );

                placed.setCount(moved);
                simulated.set(emptySlot, placed);

                remaining -= moved;
            }
        }

        return true;
    }

    private void toggleInventorySlot(int slot) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || sendInProgress) {
            return;
        }

        ItemStack stack = minecraft.player
                .getInventory()
                .getItem(slot);

        if (stack.isEmpty()) {
            selectedSlots.remove(slot);
        } else if (selectedSlots.containsKey(slot)) {
            selectedSlots.remove(slot);
        } else {
            selectedSlots.put(slot, stack.copy());
        }

        selectionStatus = "Auswahl geändert.";
    }

    private List<SerializedItem> serializeSelectedItems() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            throw new IllegalStateException("Kein Spieler verfügbar.");
        }

        var ops = minecraft.player.level()
                .registryAccess()
                .createSerializationContext(JsonOps.INSTANCE);

        List<SerializedItem> result = new ArrayList<>();

        for (Map.Entry<Integer, ItemStack> entry : selectedSlots.entrySet()) {
            int slot = entry.getKey();
            ItemStack selected = entry.getValue();

            ItemStack current = minecraft.player
                    .getInventory()
                    .getItem(slot);

            if (current.isEmpty()) {
                continue;
            }

            if (selected.getItem() != current.getItem()
                    || !selected.getComponents().equals(current.getComponents())) {
                continue;
            }

            int count = Math.min(
                    selected.getCount(),
                    current.getCount()
            );

            if (count <= 0) {
                continue;
            }

            ItemStack snapshot = current.copy();
            snapshot.setCount(count);

            JsonElement json = ItemStack.CODEC
                    .encodeStart(ops, snapshot)
                    .getOrThrow();

            result.add(new SerializedItem(
                    slot,
                    count,
                    json
            ));
        }

        return List.copyOf(result);
    }

    @Override
    public boolean mouseClicked(
            MouseButtonEvent event,
            boolean doubleClick
    ) {
        if (activeTab == Tab.SEND && event.button() == 0) {
            int slot = getInventorySlot(event.x(), event.y());

            if (slot >= 0) {
                toggleInventorySlot(slot);
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    private int getInventorySlot(double mouseX, double mouseY) {
        int relativeX = (int) mouseX - inventoryStartX;
        int relativeY = (int) mouseY - inventoryStartY;

        if (relativeX < 0 || relativeY < 0) {
            return -1;
        }

        int columnStep = SLOT_SIZE + SLOT_GAP;
        int rowStep = SLOT_SIZE + SLOT_GAP;

        int column = relativeX / columnStep;
        int row = relativeY / rowStep;

        if (column >= INVENTORY_COLUMNS || row >= 4) {
            return -1;
        }

        if (relativeX % columnStep >= SLOT_SIZE
                || relativeY % rowStep >= SLOT_SIZE) {
            return -1;
        }

        return row * INVENTORY_COLUMNS + column;
    }

    private void renderInventorySlots(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return;
        }

        for (int slot = 0; slot < 36; slot++) {
            int column = slot % INVENTORY_COLUMNS;
            int row = slot / INVENTORY_COLUMNS;

            int x = inventoryStartX + column * (SLOT_SIZE + SLOT_GAP);
            int y = inventoryStartY + row * (SLOT_SIZE + SLOT_GAP);

            ItemStack stack = minecraft.player
                    .getInventory()
                    .getItem(slot);

            boolean selected = selectedSlots.containsKey(slot);

            boolean hovered = mouseX >= x
                    && mouseX < x + SLOT_SIZE
                    && mouseY >= y
                    && mouseY < y + SLOT_SIZE;

            int background = selected
                    ? 0xFF315D43
                    : hovered
                    ? 0xFF59677D
                    : 0xFF303A4B;

            graphics.fill(
                    x,
                    y,
                    x + SLOT_SIZE,
                    y + SLOT_SIZE,
                    background
            );

            if (selected) {
                graphics.fill(x, y, x + SLOT_SIZE, y + 1, 0xFF8FE0A0);
                graphics.fill(
                        x,
                        y + SLOT_SIZE - 1,
                        x + SLOT_SIZE,
                        y + SLOT_SIZE,
                        0xFF8FE0A0
                );
                graphics.fill(x, y, x + 1, y + SLOT_SIZE, 0xFF8FE0A0);
                graphics.fill(
                        x + SLOT_SIZE - 1,
                        y,
                        x + SLOT_SIZE,
                        y + SLOT_SIZE,
                        0xFF8FE0A0
                );
            }

            if (!stack.isEmpty()) {
                graphics.item(stack, x + 2, y + 2);
                graphics.itemDecorations(
                        this.font,
                        stack,
                        x + 2,
                        y + 2
                );

                if (hovered) {
                    graphics.setTooltipForNextFrame(
                            this.font,
                            stack,
                            mouseX,
                            mouseY
                    );
                }
            }
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        graphics.fill(
                0,
                0,
                this.width,
                this.height,
                0xB8000000
        );

        graphics.fill(
                panelLeft,
                panelTop,
                panelLeft + panelWidth,
                panelTop + panelHeight,
                0xFF171B25
        );

        graphics.fill(
                panelLeft + 1,
                panelTop + 1,
                panelLeft + 126,
                panelTop + panelHeight - 1,
                0xFF202633
        );

        graphics.fill(
                panelLeft + 126,
                panelTop + 1,
                panelLeft + panelWidth - 1,
                panelTop + 42,
                0xFF252D3B
        );

        graphics.fill(
                panelLeft + 126,
                panelTop + 42,
                panelLeft + panelWidth - 1,
                panelTop + 43,
                0xFF39465A
        );

        graphics.centeredText(
                this.font,
                Component.literal("MINECRAFT PARCEL"),
                panelLeft + 69,
                panelTop + 19,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.literal("Dein Postdienst"),
                contentCenter,
                panelTop + 14,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.literal("WELTÜBERGREIFENDER ITEM-VERSAND"),
                contentCenter,
                panelTop + 27,
                0xFF9AAAC2
        );

        renderActiveTab(graphics);

        super.extractRenderState(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );

        if (activeTab == Tab.SEND) {
            renderInventorySlots(graphics, mouseX, mouseY);
        }
    }

    private void renderActiveTab(GuiGraphicsExtractor graphics) {
        int contentTop = panelTop + 60;

        switch (activeTab) {
            case INBOX -> renderInbox(graphics, contentTop);
            case SEND -> renderSend(graphics, contentTop);

            case FRIENDS -> {
                graphics.centeredText(
                        this.font,
                        Component.literal("Deine Freunde"),
                        contentCenter,
                        contentTop,
                        0xFFFFFFFF
                );

                graphics.centeredText(
                        this.font,
                        Component.literal(
                                "Die Freundeslisten-Anbindung ist noch nicht fertig."
                        ),
                        contentCenter,
                        contentTop + 28,
                        0xFFFFD27A
                );
            }
        }

        graphics.fill(
                panelLeft + 126,
                panelTop + panelHeight - 34,
                panelLeft + panelWidth - 1,
                panelTop + panelHeight - 33,
                0xFF39465A
        );

        graphics.centeredText(
                this.font,
                Component.literal("Minecraft Parcel | localhost:8787"),
                contentCenter,
                panelTop + panelHeight - 23,
                0xFF8492A8
        );
    }

    private void renderSend(
            GuiGraphicsExtractor graphics,
            int contentTop
    ) {
        graphics.centeredText(
                this.font,
                Component.literal("Paket versenden"),
                contentCenter,
                contentTop,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.literal("Empfänger-UUID"),
                contentCenter,
                panelTop + 70,
                0xFFB8C4D8
        );

        int selectedCount = 0;
        int totalCount = 0;

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player != null) {
            for (Map.Entry<Integer, ItemStack> entry : selectedSlots.entrySet()) {
                ItemStack current = minecraft.player
                        .getInventory()
                        .getItem(entry.getKey());

                ItemStack selected = entry.getValue();

                if (!current.isEmpty()
                        && current.getItem() == selected.getItem()
                        && current.getComponents().equals(selected.getComponents())) {
                    selectedCount++;
                    totalCount += Math.min(
                            current.getCount(),
                            selected.getCount()
                    );
                }
            }
        }

        graphics.centeredText(
                this.font,
                Component.literal(
                        selectedCount + " Stapel | " + totalCount + " Items"
                ),
                contentCenter,
                contentTop + 65,
                0xFF8FE0A0
        );

        graphics.centeredText(
                this.font,
                Component.literal(selectionStatus),
                contentCenter,
                panelTop + panelHeight - 45,
                0xFFB8C4D8
        );
    }

    private void renderInbox(
            GuiGraphicsExtractor graphics,
            int contentTop
    ) {
        graphics.centeredText(
                this.font,
                Component.literal("Dein Postfach"),
                contentCenter,
                contentTop,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.literal(inboxStatus),
                contentCenter,
                contentTop + 29,
                inboxLoading
                        ? 0xFFFFD27A
                        : deliveries.isEmpty()
                        ? 0xFFB8C4D8
                        : 0xFF8FE0A0
        );

        int rowTop = contentTop + 55;
        int availableRows = Math.min(deliveries.size(), 4);

        for (int i = 0; i < availableRows; i++) {
            ParcelApiClient.Delivery delivery = deliveries.get(i);
            int y = rowTop + i * 38;

            graphics.fill(
                    panelLeft + 144,
                    y,
                    panelLeft + panelWidth - 18,
                    y + 32,
                    0xFF252D3B
            );

            graphics.centeredText(
                    this.font,
                    Component.literal(
                            "Paket " + shortUuid(delivery.id())
                    ),
                    panelLeft + 210,
                    y + 5,
                    0xFFFFFFFF
            );

            graphics.centeredText(
                    this.font,
                    Component.literal(
                            delivery.itemStackCount()
                                    + " Stapel | Absender: "
                                    + shortUuid(delivery.senderUuid())
                    ),
                    panelLeft + 210,
                    y + 18,
                    0xFFB8C4D8
            );
        }
    }

    private static String shortUuid(String value) {
        if (value == null || value.isBlank()) {
            return "unbekannt";
        }

        return value.length() > 8
                ? value.substring(0, 8)
                : value;
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;

        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
