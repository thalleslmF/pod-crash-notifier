package io.crashnotifier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crashnotifier.crd.AuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Handles token refresh for webhook authentication.
 * Calls the configured refresh endpoint and extracts new tokens from the response.
 */
public class TokenRefresher {
    private static final Logger log = LoggerFactory.getLogger(TokenRefresher.class);
    private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper mapper = new ObjectMapper();

    public record RefreshResult(String token, String refreshToken, boolean changed) {}

    /**
     * Calls the refresh endpoint and returns updated tokens if they changed.
     */
    public Optional<RefreshResult> refresh(AuthConfig auth) {
        if (auth == null || auth.getRefreshUrl() == null) {
            return Optional.empty();
        }

        try {
            String body = resolveAuthPlaceholders(auth.getRefreshBody(), auth);
            log.debug("Calling auth refresh: POST {}", auth.getRefreshUrl());

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(auth.getRefreshUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Auth refresh failed: status={} body={}", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            String newToken = extractJsonPath(root, auth.getTokenJsonPath());
            String newRefreshToken = extractJsonPath(root, auth.getRefreshTokenJsonPath());

            if (newToken == null) {
                log.error("Auth refresh response missing token at path: {}", auth.getTokenJsonPath());
                return Optional.empty();
            }

            boolean changed = !newToken.equals(auth.getToken())
                    || (newRefreshToken != null && !newRefreshToken.equals(auth.getRefreshToken()));

            if (changed) {
                log.info("Auth tokens refreshed successfully");
            } else {
                log.debug("Auth tokens still valid, no refresh needed");
            }

            return Optional.of(new RefreshResult(
                    newToken,
                    newRefreshToken != null ? newRefreshToken : auth.getRefreshToken(),
                    changed
            ));
        } catch (Exception e) {
            log.error("Auth refresh error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Replaces ${auth.token} and ${auth.refreshToken} in a template string.
     */
    String resolveAuthPlaceholders(String template, AuthConfig auth) {
        if (template == null) return "";
        String result = template;
        if (auth.getToken() != null) {
            result = result.replace("${auth.token}", auth.getToken());
        }
        if (auth.getRefreshToken() != null) {
            result = result.replace("${auth.refreshToken}", auth.getRefreshToken());
        }
        return result;
    }

    /**
     * Extracts a value from JSON using a simple path like "$.token" or "$.data.accessToken".
     * Supports dot notation only (no arrays/filters).
     */
    String extractJsonPath(JsonNode root, String path) {
        if (path == null || root == null) return null;
        // Remove leading "$." if present
        String cleanPath = path.startsWith("$.") ? path.substring(2) : path;
        String[] parts = cleanPath.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            current = current.get(part);
            if (current == null) return null;
        }
        return current.isTextual() ? current.asText() : current.toString();
    }
}
