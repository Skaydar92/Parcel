
package dev.minecraftparcel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ParcelConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Path CONFIG_FILE =
            FMLPaths.CONFIGDIR.get().resolve("minecraftparcel.json");

    private static String protocol = "http";
    private static String host = "127.0.0.1";
    private static int port = 8787;

    private ParcelConfig() {
    }

    public static void load() throws IOException {
        if (!Files.exists(CONFIG_FILE)) {
            save(protocol, host, port);
            return;
        }

        String json = Files.readString(
                CONFIG_FILE,
                StandardCharsets.UTF_8
        );

        ParcelConfigData data = GSON.fromJson(
                json,
                ParcelConfigData.class
        );

        if (data == null) {
            throw new IOException("Die Parcel-Konfiguration ist leer.");
        }

        validate(data.protocol, data.host, data.port);

        protocol = data.protocol;
        host = data.host;
        port = data.port;
    }

    public static void save(
            String newProtocol,
            String newHost,
            int newPort
    ) throws IOException {
        validate(newProtocol, newHost, newPort);

        ParcelConfigData data = new ParcelConfigData();
        data.protocol = newProtocol;
        data.host = newHost;
        data.port = newPort;

        Files.createDirectories(CONFIG_FILE.getParent());

        Files.writeString(
                CONFIG_FILE,
                GSON.toJson(data),
                StandardCharsets.UTF_8
        );

        protocol = newProtocol;
        host = newHost;
        port = newPort;
    }

    private static void validate(
            String protocol,
            String host,
            int port
    ) {
        if (protocol == null
                || (!protocol.equals("http") && !protocol.equals("https"))) {
            throw new IllegalArgumentException(
                    "Das Protokoll muss http oder https sein."
            );
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "Die Serveradresse darf nicht leer sein."
            );
        }

        String normalizedHost = host.trim();

        if (!normalizedHost.equals(host)
                || normalizedHost.contains("://")
                || normalizedHost.contains("/")
                || normalizedHost.contains("?")
                || normalizedHost.contains("#")
                || normalizedHost.contains("@")
                || normalizedHost.contains(" ")) {
            throw new IllegalArgumentException(
                    "Die Serveradresse ist ungültig."
            );
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Der Port muss zwischen 1 und 65535 liegen."
            );
        }

        String uriHost = normalizedHost;

        if (uriHost.contains(":")
                && !uriHost.startsWith("[")) {
            uriHost = "[" + uriHost + "]";
        }

        URI uri = URI.create(
                protocol + "://" + uriHost + ":" + port + "/"
        );

        if (uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Die Serveradresse ist ungültig."
            );
        }
    }

    public static URI getBaseUri() {
        String uriHost = host;

        if (uriHost.contains(":")
                && !uriHost.startsWith("[")) {
            uriHost = "[" + uriHost + "]";
        }

        return URI.create(
                protocol + "://" + uriHost + ":" + port + "/"
        );
    }

    public static String getProtocol() {
        return protocol;
    }

    public static String getHost() {
        return host;
    }

    public static int getPort() {
        return port;
    }

    private static final class ParcelConfigData {
        private String protocol = "http";
        private String host = "127.0.0.1";
        private int port = 8787;
    }
}
