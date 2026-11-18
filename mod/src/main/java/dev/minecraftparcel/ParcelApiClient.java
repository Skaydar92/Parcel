
package dev.minecraftparcel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ParcelApiClient {

    private static final URI BASE_URI =
            URI.create("http://localhost:8787");

    private final HttpClient http;
    private final String bearerToken;

    private ParcelApiClient(String bearerToken) {
        this.bearerToken = bearerToken;

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static ParcelApiClient createLocal() throws IOException {
        List<Path> candidates = List.of(
                Path.of("parcel-token.txt"),
                Path.of("run", "parcel-token.txt"),
                Path.of("mod", "run", "parcel-token.txt")
        );

        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                String token = Files.readString(path).trim();

                if (!token.isEmpty()) {
                    return new ParcelApiClient(token);
                }
            }
        }

        throw new IOException(
                "Token-Datei nicht gefunden. Erwartet wird run/parcel-token.txt."
        );
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        return HttpRequest.newBuilder(BASE_URI.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + bearerToken)
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
                                getString(item, "senderUuid"),
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
            String recipientUuid,
            JsonArray items,
            String requestId
    ) {
        JsonObject payload = new JsonObject();

        payload.addProperty("recipientUuid", recipientUuid);
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
                        getString(root, "status")
                );

            } catch (IOException exception) {
                if (response.statusCode() == 200) {
                    try {
                        JsonObject root = parseResponse(response, 200);

                        return new SendResult(
                                getString(root, "deliveryId"),
                                getString(root, "status")
                        );
                    } catch (IOException ignored) {
                        // Die ursprüngliche HTTP-Fehlermeldung wird weitergegeben.
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
                        ? root.getAsJsonArray("items")
                        : new JsonArray();

                return new ClaimedDelivery(
                        getString(root, "deliveryId"),
                        getString(root, "claimToken"),
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
            String senderUuid,
            String createdAt,
            int itemStackCount
    ) {}

    public record SendResult(
            String deliveryId,
            String status
    ) {}

    public record ClaimedDelivery(
            String deliveryId,
            String claimToken,
            JsonArray items
    ) {}
}
