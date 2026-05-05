package io.crashnotifier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.crashnotifier.crd.AuthConfig;
import io.crashnotifier.crd.WebhookConfig;
import io.crashnotifier.crd.WebhookHeader;
import io.crashnotifier.domain.PodProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WebhookNotifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper mapper = new ObjectMapper();

    private final TokenRefresher tokenRefresher = new TokenRefresher();

    /**
     * Result of a notify call, including whether auth tokens were refreshed.
     */
    public record NotifyResult(boolean webhookSent, boolean tokensRefreshed, String newToken, String newRefreshToken) {
        public static NotifyResult success() { return new NotifyResult(true, false, null, null); }
        public static NotifyResult failure() { return new NotifyResult(false, false, null, null); }
        public static NotifyResult successWithRefresh(String token, String refreshToken) {
            return new NotifyResult(true, true, token, refreshToken);
        }
        public static NotifyResult failureWithRefresh(String token, String refreshToken) {
            return new NotifyResult(false, true, token, refreshToken);
        }
    }

    public NotifyResult notify(WebhookConfig config, PodProblem problem) {
        AuthConfig auth = config.getAuth();
        String currentToken = null;
        String currentRefreshToken = null;
        boolean tokensRefreshed = false;

        // Refresh auth tokens if auth is configured
        if (auth != null) {
            var refreshResult = tokenRefresher.refresh(auth);
            if (refreshResult.isPresent()) {
                var result = refreshResult.get();
                currentToken = result.token();
                currentRefreshToken = result.refreshToken();
                tokensRefreshed = result.changed();
            } else {
                // Refresh failed but we can still try with existing token
                currentToken = auth.getToken();
                currentRefreshToken = auth.getRefreshToken();
            }
        }

        try {
            String payload = buildPayload(config.getBodyTemplate(), problem);
            log.debug("Webhook payload for {}/{}: {}", problem.namespace(), problem.podName(), payload);
            var rb = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (config.getHeaders() != null) {
                for (WebhookHeader h : config.getHeaders()) {
                    if (h.getValue() != null) {
                        String value = resolveAuthHeaderPlaceholders(h.getValue(), currentToken, currentRefreshToken);
                        rb.header(h.getName(), value);
                    }
                }
            }

            var resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("Webhook sent for pod {}/{}", problem.namespace(), problem.podName());
                return tokensRefreshed
                        ? NotifyResult.successWithRefresh(currentToken, currentRefreshToken)
                        : NotifyResult.success();
            }
            log.error("Webhook status {} for {}/{}: {}", resp.statusCode(), problem.namespace(), problem.podName(), resp.body());
            return tokensRefreshed
                    ? NotifyResult.failureWithRefresh(currentToken, currentRefreshToken)
                    : NotifyResult.failure();
        } catch (Exception e) {
            log.error("Webhook failed for {}/{}: {}", problem.namespace(), problem.podName(), e.getMessage());
            return tokensRefreshed
                    ? NotifyResult.failureWithRefresh(currentToken, currentRefreshToken)
                    : NotifyResult.failure();
        }
    }

    /**
     * Replaces ${auth.token} and ${auth.refreshToken} in header values.
     */
    String resolveAuthHeaderPlaceholders(String value, String token, String refreshToken) {
        if (value == null) return null;
        String result = value;
        if (token != null) {
            result = result.replace("${auth.token}", token);
        }
        if (refreshToken != null) {
            result = result.replace("${auth.refreshToken}", refreshToken);
        }
        return result;
    }

    String resolveTemplate(String template, PodProblem p) {
        return template
                .replace("${podName}", escJson(p.podName()))
                .replace("${namespace}", escJson(p.namespace()))
                .replace("${problemType}", escJson(p.problemType()))
                .replace("${containerName}", escJson(p.containerName()))
                .replace("${imageName}", escJson(p.imageName()))
                .replace("${restartCount}", String.valueOf(p.restartCount()))
                .replace("${message}", escJson(p.message()))
                .replace("${events}", escJson(p.events()))
                .replace("${logs}", escJson(p.logs()));
    }

    private String escJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    String buildPayload(String bodyTemplate, PodProblem p) {
        if (bodyTemplate != null && !bodyTemplate.isBlank()) return resolveTemplate(bodyTemplate, p);
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("podName", p.podName());
            node.put("namespace", p.namespace());
            node.put("problemType", p.problemType());
            node.put("containerName", p.containerName());
            node.put("imageName", p.imageName());
            node.put("restartCount", p.restartCount());
            node.put("message", p.message());
            node.put("events", p.events());
            node.put("logs", p.logs());
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to build JSON payload: {}", e.getMessage());
            return "{}";
        }
    }
}
