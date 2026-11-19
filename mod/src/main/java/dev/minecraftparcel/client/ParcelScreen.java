
package dev.minecraftparcel.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.minecraftparcel.ParcelApiClient;
import dev.minecraftparcel.ParcelConfig;
import dev.minecraftparcel.ParcelNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

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
        FRIENDS,
        SETTINGS
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

    private final Map<String, ResolvableProfile> senderProfiles =
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
    private boolean connectionTesting;

    private List<ParcelApiClient.Delivery> deliveries = List.of();

    private Component inboxStatus =
            Component.translatable("gui.minecraftparcel.status.not_loaded");

    private Component selectionStatus =
            Component.translatable("gui.minecraftparcel.status.select_items");

    private Component settingsStatus =
            Component.translatable("gui.minecraftparcel.status.not_tested");

    private String settingsStatusKey =
            "gui.minecraftparcel.status.not_tested";

    private EditBox recipientField;
    private EditBox apiProtocolField;
    private EditBox apiHostField;
    private EditBox apiPortField;

    private record SerializedItem(
            int slot,
            int count,
            JsonElement data
    ) {}

    public ParcelScreen(Screen parent) {
        super(Component.translatable("screen.minecraftparcel.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(500, this.width - 20);
        panelHeight = Math.min(340, this.height - 20);

        panelLeft = (this.width - panelWidth) / 2;
        panelTop = (this.height - panelHeight) / 2;

        contentCenter = panelLeft + 126
                + (panelWidth - 126) / 2;

        inventoryStartX = contentCenter - GRID_WIDTH / 2;
        inventoryStartY = panelTop + 145;

        int sidebarX = panelLeft + 10;

        addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftparcel.tab.inbox"),
                button -> changeTab(Tab.INBOX)
        ).bounds(
                sidebarX,
                panelTop + 52,
                106,
                22
        ).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftparcel.tab.send"),
                button -> changeTab(Tab.SEND)
        ).bounds(
                sidebarX,
                panelTop + 80,
                106,
                22
        ).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftparcel.tab.friends"),
                button -> changeTab(Tab.FRIENDS)
        ).bounds(
                sidebarX,
                panelTop + 108,
                106,
                22
        ).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftparcel.tab.settings"),
                button -> changeTab(Tab.SETTINGS)
        ).bounds(
                sidebarX,
                panelTop + 136,
                106,
                22
        ).build());

        if (activeTab == Tab.INBOX) {
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.minecraftparcel.button.refresh"),
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
                        Component.translatable("gui.minecraftparcel.button.claim"),
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
                    Component.translatable("gui.minecraftparcel.field.recipient")
            );

            recipientField.setMaxLength(16);
            recipientField.setHint(
                    Component.translatable("gui.minecraftparcel.field.player_name_hint")
            );

            addRenderableWidget(recipientField);

            addRenderableWidget(Button.builder(
                    Component.translatable("gui.minecraftparcel.button.send"),
                    button -> sendParcel()
            ).bounds(
                    panelLeft + panelWidth - 110,
                    panelTop + 49,
                    98,
                    20
            ).build());
        }

        if (activeTab == Tab.SETTINGS) {
            apiProtocolField = new EditBox(
                    this.font,
                    contentCenter - 110,
                    panelTop + 83,
                    75,
                    20,
                    Component.translatable("gui.minecraftparcel.field.protocol")
            );

            apiProtocolField.setMaxLength(5);
            apiProtocolField.setValue(ParcelConfig.getProtocol());
            apiProtocolField.setHint(Component.literal("http"));

            addRenderableWidget(apiProtocolField);

            apiHostField = new EditBox(
                    this.font,
                    contentCenter - 25,
                    panelTop + 83,
                    155,
                    20,
                    Component.translatable("gui.minecraftparcel.field.server_address")
            );

            apiHostField.setMaxLength(253);
            apiHostField.setValue(ParcelConfig.getHost());
            apiHostField.setHint(Component.literal("127.0.0.1"));

            addRenderableWidget(apiHostField);

            apiPortField = new EditBox(
                    this.font,
                    contentCenter - 25,
                    panelTop + 123,
                    80,
                    20,
                    Component.translatable("gui.minecraftparcel.field.port")
            );

            apiPortField.setMaxLength(5);
            apiPortField.setValue(
                    String.valueOf(ParcelConfig.getPort())
            );
            apiPortField.setHint(Component.literal("8787"));

            addRenderableWidget(apiPortField);

            addRenderableWidget(Button.builder(
                    Component.translatable("gui.minecraftparcel.button.save"),
                    button -> saveApiSettings()
            ).bounds(
                    contentCenter - 110,
                    panelTop + 165,
                    105,
                    20
            ).build());

            addRenderableWidget(Button.builder(
                    Component.translatable("gui.minecraftparcel.button.test_connection"),
                    button -> testApiConnection()
            ).bounds(
                    contentCenter + 2,
                    panelTop + 165,
                    128,
                    20
            ).build());
        }

        addRenderableWidget(Button.builder(
                Component.translatable("gui.minecraftparcel.button.close"),
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

    private void setSettingsStatus(String key, Object... arguments) {
        settingsStatusKey = key;
        settingsStatus = Component.translatable(key, arguments);
    }

    private void saveApiSettings() {
        if (apiProtocolField == null
                || apiHostField == null
                || apiPortField == null) {
            return;
        }

        String protocol = apiProtocolField.getValue().trim()
                .toLowerCase();

        String host = apiHostField.getValue().trim();
        String portText = apiPortField.getValue().trim();

        int port;

        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            setSettingsStatus("gui.minecraftparcel.status.invalid_port");
            return;
        }

        try {
            ParcelConfig.save(protocol, host, port);

            setSettingsStatus("gui.minecraftparcel.status.settings_saved");
            inboxRequested = false;
        } catch (IOException | IllegalArgumentException exception) {
            setSettingsStatus(
                    "gui.minecraftparcel.status.error",
                    exception.getMessage()
            );
        }
    }

    private void testApiConnection() {
        if (connectionTesting) {
            return;
        }

        saveApiSettings();

        if (!"gui.minecraftparcel.status.settings_saved".equals(settingsStatusKey)) {
            return;
        }

        connectionTesting = true;
        setSettingsStatus("gui.minecraftparcel.status.testing_connection");

        ParcelApiClient.testConnection().whenComplete(
                (result, error) ->
                        Minecraft.getInstance().execute(() -> {
                            connectionTesting = false;

                            if (error != null) {
                                setSettingsStatus(
                                        "gui.minecraftparcel.status.connection_failed",
                                        errorMessage(error)
                                );
                            } else {
                                setSettingsStatus(
                                        "gui.minecraftparcel.status.connection_success"
                                );
                            }
                        })
        );
    }

    private void refreshInbox() {
        if (inboxLoading) {
            return;
        }

        inboxRequested = true;
        inboxLoading = true;

        inboxStatus = Component.translatable(
                "gui.minecraftparcel.status.connecting",
                ParcelConfig.getHost(),
                ParcelConfig.getPort()
        );

        try {
            ParcelApiClient client = ParcelApiClient.createLocal();

            client.getInbox().whenComplete((result, error) ->
                    Minecraft.getInstance().execute(() -> {
                        inboxLoading = false;

                        if (error != null) {
                            inboxStatus = Component.translatable(
                                    "gui.minecraftparcel.status.connection_failed",
                                    errorMessage(error)
                            );
                            return;
                        }

                        deliveries = result;

                        inboxStatus = result.isEmpty()
                                ? Component.translatable("gui.minecraftparcel.status.inbox_empty")
                                : Component.translatable(
                                        "gui.minecraftparcel.status.packages_found",
                                        result.size()
                                );

                        if (activeTab == Tab.INBOX) {
                            rebuildWidgets();
                        }
                    })
            );

        } catch (IOException exception) {
            inboxLoading = false;
            inboxStatus = Component.translatable(
                    "gui.minecraftparcel.status.local_configuration",
                    exception.getMessage()
            );
        }
    }

    private void sendParcel() {
        if (sendInProgress || recipientField == null) {
            return;
        }

        String recipientName = recipientField.getValue().trim();

        if (!recipientName.matches("[a-zA-Z0-9_]{3,16}")) {
            selectionStatus = Component.translatable(
                    "gui.minecraftparcel.status.invalid_player_name"
            );
            return;
        }

        List<SerializedItem> serialized;

        try {
            serialized = serializeSelectedItems();
        } catch (Exception exception) {
            selectionStatus = Component.translatable(
                    "gui.minecraftparcel.status.serialization_failed",
                    exception.getMessage()
            );
            return;
        }

        if (serialized.isEmpty()) {
            selectionStatus = Component.translatable(
                    "gui.minecraftparcel.status.no_valid_items"
            );
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
        selectionStatus = Component.translatable(
                "gui.minecraftparcel.status.inventory_check"
        );

        String requestId = UUID.randomUUID().toString();

        ParcelNetwork.send(
                "REMOVE",
                removeData.toString(),
                inventoryResult -> {
                    if (!inventoryResult.success()) {
                        sendInProgress = false;
                        selectionStatus = Component.literal(inventoryResult.message());
                        return;
                    }

                    selectionStatus = Component.translatable(
                            "gui.minecraftparcel.status.inventory_confirmed"
                    );

                    try {
                        ParcelApiClient client =
                                ParcelApiClient.createLocal();

                        client.createDelivery(
                                recipientName,
                                items,
                                requestId
                        ).whenComplete((result, error) ->
                                Minecraft.getInstance().execute(() -> {
                                    if (error != null) {
                                        refundItems(
                                                serialized,
                                                requestId,
                                                errorMessage(error)
                                        );
                                        return;
                                    }

                                    sendInProgress = false;
                                    selectedSlots.clear();

                                    String confirmedName =
                                            result.recipientName().isBlank()
                                                    ? recipientName
                                                    : result.recipientName();

                                    selectionStatus = Component.translatable(
                                            "gui.minecraftparcel.status.parcel_sent",
                                            confirmedName
                                    );
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

        selectionStatus = Component.translatable(
                "gui.minecraftparcel.status.restoring_items"
        );

        ParcelNetwork.send(
                "ADD",
                restoreData.toString(),
                result -> {
                    sendInProgress = false;

                    if (result.success()) {
                        selectionStatus = Component.translatable(
                                "gui.minecraftparcel.status.send_failed_restored",
                                originalError
                        );
                    } else {
                        selectionStatus = Component.translatable(
                                "gui.minecraftparcel.status.critical_restore_failed",
                                result.message()
                        );
                    }
                }
        );
    }

    private void claimDelivery(ParcelApiClient.Delivery delivery) {
        if (inboxLoading) {
            return;
        }

        inboxLoading = true;
        inboxStatus = Component.translatable(
                "gui.minecraftparcel.status.claiming"
        );

        try {
            ParcelApiClient client = ParcelApiClient.createLocal();

            client.claimDelivery(delivery.id())
                    .whenComplete((claimed, claimError) ->
                            Minecraft.getInstance().execute(() -> {
                                if (claimError != null) {
                                    inboxLoading = false;
                                    inboxStatus = Component.translatable(
                                            "gui.minecraftparcel.status.claim_failed",
                                            errorMessage(claimError)
                                    );
                                    return;
                                }

                                receiveClaimedDelivery(client, claimed);
                            })
                    );

        } catch (IOException exception) {
            inboxLoading = false;
            inboxStatus = Component.translatable(
                    "gui.minecraftparcel.status.local_configuration",
                    exception.getMessage()
            );
        }
    }

    private void receiveClaimedDelivery(
            ParcelApiClient client,
            ParcelApiClient.ClaimedDelivery claimed
    ) {
        if (Minecraft.getInstance().player == null) {
            inboxLoading = false;
            inboxStatus = Component.translatable(
                    "gui.minecraftparcel.status.no_player_claim"
            );
            return;
        }

        if (claimed.items() == null || claimed.items().isEmpty()) {
            inboxLoading = false;
            inboxStatus = Component.translatable(
                    "gui.minecraftparcel.status.empty_package"
            );
            return;
        }

        JsonObject addData = new JsonObject();
        addData.addProperty("deliveryId", claimed.deliveryId());
        addData.add("items", claimed.items());

        inboxStatus = Component.translatable(
                "gui.minecraftparcel.status.checking_inventory_space"
        );

        ParcelNetwork.send(
                "ADD",
                addData.toString(),
                result -> {
                    if (!result.success()) {
                        inboxLoading = false;
                        inboxStatus = Component.translatable(
                                "gui.minecraftparcel.status.claim_failed",
                                result.message()
                        );
                        return;
                    }

                    inboxStatus = Component.translatable(
                            "gui.minecraftparcel.status.items_inserted"
                    );

                    client.completeClaim(
                            claimed.deliveryId(),
                            claimed.claimToken()
                    ).whenComplete((ignored, completeError) ->
                            Minecraft.getInstance().execute(() -> {
                                inboxLoading = false;

                                if (completeError != null) {
                                    inboxStatus = Component.translatable(
                                            "gui.minecraftparcel.status.claim_confirmation_failed",
                                            errorMessage(completeError)
                                    );
                                    return;
                                }

                                inboxStatus = Component.translatable(
                                        "gui.minecraftparcel.status.items_claimed",
                                        claimed.items().size()
                                );

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

        selectionStatus = Component.translatable(
                "gui.minecraftparcel.status.selection_changed"
        );
    }

    private List<SerializedItem> serializeSelectedItems() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            throw new IllegalStateException(
                    Component.translatable(
                            "gui.minecraftparcel.error.no_player"
                    ).getString()
            );
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
                Component.translatable("gui.minecraftparcel.brand"),
                panelLeft + 69,
                panelTop + 19,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.subtitle"),
                contentCenter,
                panelTop + 14,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.tagline"),
                contentCenter,
                panelTop + 27,
                0xFF9AAAC2
        );

        renderActiveTab(graphics);

        if (activeTab == Tab.SEND) {
            renderInventorySlots(graphics, mouseX, mouseY);
        }

        super.extractRenderState(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );
    }

    private void renderActiveTab(GuiGraphicsExtractor graphics) {
        int contentTop = panelTop + 60;

        switch (activeTab) {
            case INBOX -> renderInbox(graphics, contentTop);
            case SEND -> renderSend(graphics, contentTop);
            case FRIENDS -> {
                graphics.centeredText(
                        this.font,
                        Component.translatable("gui.minecraftparcel.friends.title"),
                        contentCenter,
                        contentTop,
                        0xFFFFFFFF
                );

                graphics.centeredText(
                        this.font,
                        Component.translatable("gui.minecraftparcel.friends.unavailable"),
                        contentCenter,
                        contentTop + 28,
                        0xFFFFD27A
                );
            }
            case SETTINGS -> renderSettings(graphics, contentTop);
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
                Component.translatable(
                        "gui.minecraftparcel.footer",
                        ParcelConfig.getHost(),
                        ParcelConfig.getPort()
                ),
                contentCenter,
                panelTop + panelHeight - 23,
                0xFF8492A8
        );
    }

    private void renderSettings(
            GuiGraphicsExtractor graphics,
            int contentTop
    ) {
        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.settings.title"),
                contentCenter,
                contentTop,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.field.protocol"),
                contentCenter - 72,
                panelTop + 70,
                0xFFB8C4D8
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.field.server_address"),
                contentCenter + 52,
                panelTop + 70,
                0xFFB8C4D8
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.field.port"),
                contentCenter - 25,
                panelTop + 110,
                0xFFB8C4D8
        );

        int statusColor = settingsStatusKey.equals(
                "gui.minecraftparcel.status.connection_success"
        ) || settingsStatusKey.equals(
                "gui.minecraftparcel.status.settings_saved"
        )
                ? 0xFF8FE0A0
                : settingsStatusKey.equals(
                        "gui.minecraftparcel.status.testing_connection"
                )
                ? 0xFFFFD27A
                : settingsStatusKey.equals(
                        "gui.minecraftparcel.status.error"
                ) || settingsStatusKey.equals(
                        "gui.minecraftparcel.status.connection_failed"
                ) || settingsStatusKey.equals(
                        "gui.minecraftparcel.status.invalid_port"
                )
                ? 0xFFFF7777
                : 0xFFB8C4D8;

        graphics.centeredText(
                this.font,
                settingsStatus,
                contentCenter,
                panelTop + 198,
                statusColor
        );
    }

    private void renderSend(
            GuiGraphicsExtractor graphics,
            int contentTop
    ) {
        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.send.title"),
                contentCenter,
                contentTop,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.field.recipient"),
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
                Component.translatable(
                        "gui.minecraftparcel.send.selection_count",
                        selectedCount,
                        totalCount
                ),
                contentCenter,
                panelTop + 117,
                0xFF8FE0A0
        );

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.send.inventory_hint"),
                contentCenter,
                panelTop + 134,
                0xFFB8C4D8
        );

        graphics.centeredText(
                this.font,
                selectionStatus,
                contentCenter,
                panelTop + panelHeight - 49,
                0xFFB8C4D8
        );
    }

    private void renderInbox(
            GuiGraphicsExtractor graphics,
            int contentTop
    ) {
        int contentLeft = panelLeft + 140;
        int contentRight = panelLeft + panelWidth - 16;
        int contentWidth = contentRight - contentLeft;
        int center = contentLeft + contentWidth / 2;

        graphics.centeredText(
                this.font,
                Component.translatable("gui.minecraftparcel.inbox.title"),
                center,
                contentTop,
                0xFFFFFFFF
        );

        graphics.centeredText(
                this.font,
                inboxStatus,
                center,
                contentTop + 29,
                inboxLoading
                        ? 0xFFFFD27A
                        : deliveries.isEmpty()
                        ? 0xFFB8C4D8
                        : 0xFF8FE0A0
        );

        int rowTop = contentTop + 55;
        int availableRows = Math.min(deliveries.size(), 4);

        Minecraft minecraft = Minecraft.getInstance();

        for (int i = 0; i < availableRows; i++) {
            ParcelApiClient.Delivery delivery = deliveries.get(i);
            int y = rowTop + i * 38;

            graphics.fill(
                    contentLeft,
                    y,
                    contentRight,
                    y + 32,
                    0xFF252D3B
            );

            String senderName = delivery.senderName().isBlank()
                    ? Component.translatable(
                            "gui.minecraftparcel.sender.unknown"
                    ).getString()
                    : delivery.senderName();

            if (!delivery.senderName().isBlank()) {
                ResolvableProfile profile = senderProfiles.computeIfAbsent(
                        senderName,
                        ResolvableProfile::createUnresolved
                );

                var renderInfo = minecraft.playerSkinRenderCache()
                        .getOrDefault(profile);

                PlayerFaceExtractor.extractRenderState(
                        graphics,
                        renderInfo.playerSkin(),
                        contentLeft + 6,
                        y + 6,
                        20
                );
            } else {
                graphics.fill(
                        contentLeft + 6,
                        y + 6,
                        contentLeft + 26,
                        y + 26,
                        0xFF59677D
                );
            }

            int textLeft = contentLeft + 32;
            int textRight = contentRight - 82;
            int textCenter = textLeft + (textRight - textLeft) / 2;

            graphics.centeredText(
                    this.font,
                    Component.translatable(
                            "gui.minecraftparcel.inbox.package_number",
                            i + 1
                    ),
                    textCenter,
                    y + 5,
                    0xFFFFFFFF
            );

            graphics.centeredText(
                    this.font,
                    Component.translatable(
                            "gui.minecraftparcel.inbox.package_details",
                            delivery.itemStackCount(),
                            senderName
                    ),
                    textCenter,
                    y + 18,
                    0xFFB8C4D8
            );
        }
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
