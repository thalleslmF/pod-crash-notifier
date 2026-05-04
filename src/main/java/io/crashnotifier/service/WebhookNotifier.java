package io.crashnotifier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    public boolean notify(WebhookConfig config, PodProblem problem) {
        try {
            String payload = buildPayload(config.getBodyTemplate(), problem);
            var rb = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (config.getHeaders() != null) {
                for (WebhookHeader h : config.getHeaders()) {
                    if (h.getValue() != null) rb.header(h.getName(), h.getValue());
                }
            }
            var resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("Webhook sent for pod {}/{}", problem.namespace(), problem.podName());
                return true;
            }
            log.error("Webhook status {} for {}/{}: {}", resp.statusCode(), problem.namespace(), problem.podName(), resp.body());
            return false;
        } catch (Exception e) {
            log.error("Webhook failed for {}/{}: {}", problem.namespace(), problem.podName(), e.getMessage());
            return false;
        }
    }

    String resolveTemplate(String template, PodProblem p) {
        return template
                .replace("${podName}", p.podName()).replace("${namespace}", p.namespace())
                .replace("${problemType}", p.problemType()).replace("${containerName}", p.containerName())
                .replace("${imageName}", p.imageName()).replace("${restartCount}", String.valueOf(p.restartCount()))
                .replace("${message}", p.message()).replace("${events}", p.events()).replace("${logs}", p.logs());
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
