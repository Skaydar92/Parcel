
package dev.minecraftparcel.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(
        modid = "minecraftparcel",
        value = net.neoforged.api.distmarker.Dist.CLIENT
)
public final class ParcelKeyMappings {

    public static final KeyMapping OPEN_PARCEL = new KeyMapping(
            "key.minecraftparcel.open_parcel",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_P,
            KeyMapping.Category.INVENTORY
    );

    private ParcelKeyMappings() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PARCEL);
    }
}
