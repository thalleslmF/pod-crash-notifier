# Marco 4: Servicos de Infraestrutura

> **Entregavel:** WebhookNotifier e PodDiagnosticCollector compilam, template resolution testado.

**Parent plan:** `2026-04-30-pod-crash-notifier.md`

**Mudancas vs plano original:**
- WebhookNotifier nao precisa mais de KubernetesClient (headers sao plain values, tokens via env var)
- PodProblem agora tem campo `message` (9 campos no record)

---

## Step 4.1: WebhookNotifier + testes de template

- [ ] Criar `src/test/java/io/crashnotifier/service/WebhookNotifierTest.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.domain.PodProblem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookNotifierTest {
    @Test
    void resolvesBodyTemplate() {
        var notifier = new WebhookNotifier();
        var problem = new PodProblem("my-pod", "prod", "CrashLoopBackOff", "app", "app:v1", 5, "", "ev", "lg");
        String template = "{\"input_value\": \"Pod ${podName} in ${namespace} - ${problemType}\"}";
        assertEquals("{\"input_value\": \"Pod my-pod in prod - CrashLoopBackOff\"}", notifier.resolveTemplate(template, problem));
    }

    @Test
    void resolvesAllPlaceholders() {
        var notifier = new WebhookNotifier();
        var problem = new PodProblem("p", "ns", "type", "c", "img", 3, "msg", "ev", "lg");
        String template = "${podName}-${namespace}-${problemType}-${containerName}-${imageName}-${restartCount}-${message}-${events}-${logs}";
        assertEquals("p-ns-type-c-img-3-msg-ev-lg", notifier.resolveTemplate(template, problem));
    }

    @Test
    void usesDefaultPayloadWhenNoTemplate() {
        var notifier = new WebhookNotifier();
        var problem = new PodProblem("my-pod", "prod", "CrashLoopBackOff", "app", "app:v1", 5, "msg", "events", "logs");
        String result = notifier.buildPayload(null, problem);
        assertTrue(result.contains("\"podName\":\"my-pod\""));
        assertTrue(result.contains("\"problemType\":\"CrashLoopBackOff\""));
    }
}
```

- [ ] Rodar `mvn test -Dtest=WebhookNotifierTest` -- deve FALHAR

- [ ] Criar `src/main/java/io/crashnotifier/service/WebhookNotifier.java`

```java
package io.crashnotifier.handler;

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
        return String.format(
                "{\"podName\":\"%s\",\"namespace\":\"%s\",\"problemType\":\"%s\","
                        + "\"containerName\":\"%s\",\"imageName\":\"%s\",\"restartCount\":%d,"
                        + "\"message\":\"%s\",\"events\":\"%s\",\"logs\":\"%s\"}",
                esc(p.podName()), esc(p.namespace()), esc(p.problemType()),
                esc(p.containerName()), esc(p.imageName()), p.restartCount(),
                esc(p.message()), esc(p.events()), esc(p.logs()));
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
```

- [ ] Rodar `mvn test -Dtest=WebhookNotifierTest` -- deve PASSAR
- [ ] Commit: `feat: add WebhookNotifier with template resolution and tests`

---

## Step 4.2: PodDiagnosticCollector

- [ ] Criar `src/main/java/io/crashnotifier/service/PodDiagnosticCollector.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class PodDiagnosticCollector {
    private static final Logger log = LoggerFactory.getLogger(PodDiagnosticCollector.class);
    private static final int LOG_TAIL_LINES = 50;
    private final KubernetesClient client;

    public PodDiagnosticCollector(KubernetesClient client) {
        this.client = client;
    }

    public PodProblem enrich(PodProblem p) {
        String events = collectEvents(p.podName(), p.namespace());
        String logs = collectLogs(p.podName(), p.namespace(), p.containerName());
        return new PodProblem(p.podName(), p.namespace(), p.problemType(),
                p.containerName(), p.imageName(), p.restartCount(), p.message(), events, logs);
    }

    public List<Event> getEventsForPod(String podName, String ns) {
        try {
            return client.events().v1().events().inNamespace(ns)
                    .withField("regarding.name", podName).list().getItems();
        } catch (Exception e) {
            log.warn("Events fetch failed for {}/{}: {}", ns, podName, e.getMessage());
            return List.of();
        }
    }

    private String collectEvents(String podName, String ns) {
        try {
            return client.events().v1().events().inNamespace(ns)
                    .withField("regarding.name", podName).list().getItems().stream()
                    .map(e -> String.format("[%s] %s: %s",
                            e.getDeprecatedLastTimestamp() != null ? e.getDeprecatedLastTimestamp() : e.getEventTime(),
                            e.getReason(), e.getNote() != null ? e.getNote() : e.getAction()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Events failed for {}/{}: {}", ns, podName, e.getMessage());
            return "";
        }
    }

    private String collectLogs(String podName, String ns, String container) {
        if (container == null || container.isEmpty()) return "";
        try {
            return client.pods().inNamespace(ns).withName(podName)
                    .inContainer(container).tailingLines(LOG_TAIL_LINES).terminated().getLog();
        } catch (Exception e) {
            try {
                return client.pods().inNamespace(ns).withName(podName)
                        .inContainer(container).tailingLines(LOG_TAIL_LINES).getLog();
            } catch (Exception e2) {
                log.warn("Logs failed for {}/{}/{}: {}", ns, podName, container, e2.getMessage());
                return "";
            }
        }
    }
}
```

- [ ] Rodar `mvn compile` -- deve passar
- [ ] Commit: `feat: add PodDiagnosticCollector for events and logs`
