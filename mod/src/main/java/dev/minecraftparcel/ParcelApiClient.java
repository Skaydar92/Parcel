
package dev.minecraftparcel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ParcelApiClient {

    private final URI baseUri;
    private final HttpClient http;
    private final String playerName;

    private ParcelApiClient(String playerName, URI baseUri) {
        this.playerName = playerName;
        this.baseUri = baseUri;

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static ParcelApiClient createLocal() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.getUser() == null) {
            throw new IOException(
                    "Minecraft-Spieler konnte nicht ermittelt werden."
            );
        }

        String playerName = minecraft.getUser().getName();

        if (playerName == null || playerName.isBlank()) {
            throw new IOException(
                    "Minecraft-Spielername ist nicht verfügbar."
            );
        }

        System.out.println("Parcel Player: " + playerName);
        System.out.println(
                "Parcel UUID: " + minecraft.getUser().getProfileId()
        );

        try {
            return new ParcelApiClient(
                    playerName,
                    ParcelConfig.getBaseUri()
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Ungültige Parcel-Serveradresse.",
                    exception
            );
        }
    }

    public static CompletableFuture<String> testConnection() {
        URI uri;

        try {
            uri = ParcelConfig.getBaseUri().resolve("/health");
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        return client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new CompletionException(
                        new IOException(
                                "HTTP " + response.statusCode()
                        )
                );
            }

            try {
                JsonObject json = JsonParser.parseString(response.body())
                        .getAsJsonObject();

                if (json.has("ok")
                        && json.get("ok").getAsBoolean()) {
                    return "Verbindung erfolgreich.";
                }

                throw new IOException(
                        "Ungültige Health-Antwort vom Server."
                );
            } catch (RuntimeException exception) {
                throw new CompletionException(
                        new IOException(
                                "Ungültige Serverantwort.",
                                exception
                        )
                );
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("X-Parcel-Player", playerName)
                .header("Accept", "application/json");
    }

    private static JsonObject parseResponse(
            HttpResponse<String> response,
            int expectedStatus
    ) throws IOException {
        if (response.statusCode() != expectedStatus) {
            String message = "Parcel API antwortete mit HTTP "
                    + response.statusCode();

            try {
                JsonObject error = JsonParser.parseString(response.body())
                        .getAsJsonObject();

                if (error.has("error")) {
                    message += ": " + error.get("error").getAsString();
                }
            } catch (RuntimeException ignored) {
                // Die HTTP-Fehlermeldung bleibt erhalten.
            }

            throw new IOException(message);
        }

        try {
            return JsonParser.parseString(response.body())
                    .getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Ungültige Antwort vom Parcel-Server",
                    exception
            );
        }
    }

    public CompletableFuture<List<Delivery>> getInbox() {
        HttpRequest request = authorizedRequest("/v1/inbox")
                .GET()
                .build();

        return http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            try {
                JsonObject root = parseResponse(response, 200);
                JsonArray deliveries = root.getAsJsonArray("deliveries");

                List<Delivery> result = new ArrayList<>();

                if (deliveries != null) {
                    for (JsonElement element : deliveries) {
                        JsonObject item = element.getAsJsonObject();

                        JsonArray stacks = item.has("items")
                                && item.get("items").isJsonArray()
                                ? item.getAsJsonArray("items")
                                : new JsonArray();

                        result.add(new Delivery(
                                getString(item, "id"),
                                getString(item, "senderName"),
                                getString(item, "createdAt"),
                                stacks.size()
                        ));
                    }
                }

                return List.copyOf(result);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<SendResult> createDelivery(
            String recipientName,
            JsonArray items,
            String requestId
    ) {
        JsonObject payload = new JsonObject();

        payload.addProperty("recipientName", recipientName);
        payload.addProperty("requestId", requestId);
        payload.add("items", items);

        HttpRequest request = authorizedRequest("/v1/deliveries")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            try {
                JsonObject root = parseResponse(response, 201);

                return new SendResult(
                        getString(root, "deliveryId"),
                        getString(root, "status"),
                        getString(root, "recipientName")
                );
            } catch (IOException exception) {
                if (response.statusCode() == 200) {
                    try {
                        JsonObject root = parseResponse(response, 200);

                        return new SendResult(
                                getString(root, "deliveryId"),
                                getString(root, "status"),
                                getString(root, "recipientName")
                        );
                    } catch (IOException ignored) {
                        // Ursprüngliche HTTP-Fehlermeldung weitergeben.
                    }
                }

                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<ClaimedDelivery> claimDelivery(String deliveryId) {
        HttpRequest request = authorizedRequest(
                "/v1/deliveries/" + deliveryId + "/claim"
        )
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        return http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            try {
                JsonObject root = parseResponse(response, 200);

                JsonArray items = root.has("items")
                        && root.get("items").isJsonArray()
                        ? root.getAsJsonArray("items")
                        : new JsonArray();

                return new ClaimedDelivery(
                        getString(root, "deliveryId"),
                        getString(root, "claimToken"),
                        getString(root, "senderName"),
                        items
                );
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<Void> completeClaim(
            String deliveryId,
            String claimToken
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("claimToken", claimToken);

        HttpRequest request = authorizedRequest(
                "/v1/deliveries/" + deliveryId + "/complete"
        )
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            try {
                parseResponse(response, 200);
                return null;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private static String getString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : "";
    }

    public record Delivery(
            String id,
            String senderName,
            String createdAt,
            int itemStackCount
    ) {}

    public record SendResult(
            String deliveryId,
            String status,
            String recipientName
    ) {}

    public record ClaimedDelivery(
            String deliveryId,
            String claimToken,
            String senderName,
            JsonArray items
    ) {}
}
