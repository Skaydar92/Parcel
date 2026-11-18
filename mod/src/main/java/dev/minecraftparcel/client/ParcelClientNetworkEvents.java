
package dev.minecraftparcel.client;

import dev.minecraftparcel.MinecraftParcel;
import dev.minecraftparcel.ParcelNetwork;
import dev.minecraftparcel.ParcelPayloads;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(
        modid = MinecraftParcel.MOD_ID,
        value = Dist.CLIENT
)
public final class ParcelClientNetworkEvents {

    private ParcelClientNetworkEvents() {}

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(
                ParcelPayloads.InventoryResult.TYPE,
                (result, context) -> ParcelNetwork.handleResult(result)
        );
    }
}
