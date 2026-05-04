package io.crashnotifier.service;

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

    public PodProblem enrich(PodProblem p, String podPhase) {
        String events = collectEvents(p.podName(), p.namespace());
        String logs = "Running".equals(podPhase) ? collectLogs(p.podName(), p.namespace(), p.containerName()) : "";
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
                    .inContainer(container).terminated().tailingLines(LOG_TAIL_LINES).getLog();
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
