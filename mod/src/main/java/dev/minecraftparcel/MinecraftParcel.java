
package dev.minecraftparcel;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Main NeoForge entry point for Minecraft Parcel.
 */
@Mod(MinecraftParcel.MOD_ID)
public final class MinecraftParcel {

    public static final String MOD_ID = "minecraftparcel";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MinecraftParcel(IEventBus modEventBus) {
        try {
            ParcelConfig.load();
            LOGGER.info(
                    "Parcel API configured: {}",
                    ParcelConfig.getBaseUri()
            );
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error(
                    "Could not load Parcel configuration. Using defaults.",
                    exception
            );
        }

        LOGGER.info(
                "Minecraft Parcel {} initialized",
                BuildInfo.VERSION
        );

        modEventBus.addListener(ParcelServerHandler::register);
    }

    private static final class BuildInfo {
        private static final String VERSION = "0.2.0";
    }
}
