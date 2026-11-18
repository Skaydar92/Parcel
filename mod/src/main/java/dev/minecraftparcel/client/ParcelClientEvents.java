package dev.minecraftparcel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Client-only keybind and inventory button registration for Minecraft Parcel. */
@EventBusSubscriber(modid = "minecraftparcel", value = Dist.CLIENT)
public final class ParcelClientEvents {
    private ParcelClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (ParcelKeyMappings.OPEN_PARCEL.consumeClick()) {
            if (minecraft.player != null && minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new ParcelScreen(null));
            }
        }
    }

    @SubscribeEvent
    public static void onInventoryScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof InventoryScreen)) {
            return;
        }

        // The player inventory GUI is 176x166 and centered. Place the button
        // immediately below it so it does not cover armor slots or inventory slots.
        int x = screen.width / 2 - 50;
        int y = screen.height / 2 + 85;
        Button parcelButton = Button.builder(
                        Component.translatable("gui.minecraftparcel.open"),
                        button -> Minecraft.getInstance().gui.setScreen(new ParcelScreen(screen))
                )
                .bounds(x, y, 100, 20)
                .build();
        event.addListener(parcelButton);
    }
}
