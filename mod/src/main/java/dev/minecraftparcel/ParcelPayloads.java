
package dev.minecraftparcel;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.network.RegistryFriendlyByteBuf;

public final class ParcelPayloads {

    private ParcelPayloads() {}

    public record InventoryRequest(
            String requestId,
            String operation,
            String data
    ) implements CustomPacketPayload {

        public static final Type<InventoryRequest> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(
                        MinecraftParcel.MOD_ID,
                        "inventory_request"
                ));

        public static final StreamCodec<RegistryFriendlyByteBuf, InventoryRequest> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, InventoryRequest::requestId,
                        ByteBufCodecs.STRING_UTF8, InventoryRequest::operation,
                        ByteBufCodecs.STRING_UTF8, InventoryRequest::data,
                        InventoryRequest::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record InventoryResult(
            String requestId,
            boolean success,
            String message
    ) implements CustomPacketPayload {

        public static final Type<InventoryResult> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(
                        MinecraftParcel.MOD_ID,
                        "inventory_result"
                ));

        public static final StreamCodec<RegistryFriendlyByteBuf, InventoryResult> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, InventoryResult::requestId,
                        ByteBufCodecs.BOOL, InventoryResult::success,
                        ByteBufCodecs.STRING_UTF8, InventoryResult::message,
                        InventoryResult::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
