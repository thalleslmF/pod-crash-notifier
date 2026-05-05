# Marco 3: Logica de Deteccao (com testes)

> **Entregavel:** PodHealthEvaluator e AlertDeduplicator funcionando e testados.

**Parent plan:** `2026-04-30-pod-crash-notifier.md`

**Deteccoes suportadas:**
1. CrashLoopBackOff -- waiting reason
2. RestartThreshold -- restarts > N (container running)
3. HealthCheckFailing -- events Unhealthy (Liveness/Readiness probe failed)
4. Unschedulable -- Pending + PodScheduled=False + threshold de tempo
5. ImagePullBackOff / ErrImagePull -- waiting reason
6. OOMKilled -- last terminated reason = OOMKilled
7. CreateContainerConfigError -- waiting reason
8. InitContainerFailing -- init container em waiting com reason de erro

---

## Step 3.1: PodProblem model + PodHealthEvaluator + testes

- [ ] Criar `src/main/java/io/crashnotifier/model/PodProblem.java`

```java
package io.crashnotifier.domain;

public record PodProblem(
        String podName, String namespace, String problemType,
        String containerName, String imageName, int restartCount,
        String message, String events, String logs
) {
}
```

- [ ] Criar `src/test/java/io/crashnotifier/service/PodHealthEvaluatorTest.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.DetectionConfig;
import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.events.v1.EventBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PodHealthEvaluatorTest {

    private final PodHealthHandler evaluator = new PodHealthHandler();

    @Test
    void detectsCrashLoopBackOff() {
        Pod pod = buildPodWithWaitingReason("CrashLoopBackOff", 5);
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertEquals(1, problems.size());
        assertEquals("CrashLoopBackOff", problems.get(0).problemType());
    }

    @Test
    void detectsRestartThresholdExceeded() {
        Pod pod = buildRunningPodWithRestarts(10);
        DetectionConfig config = new DetectionConfig();
        config.setRestartThreshold(5);
        List<PodProblem> problems = evaluator.evaluate(pod, config, List.of());
        assertEquals(1, problems.size());
        assertEquals("RestartThreshold", problems.get(0).problemType());
    }

    @Test
    void detectsHealthCheckFailingViaEvents() {
        Pod pod = buildRunningNotReadyPod();
        Event unhealthyEvent = new EventBuilder()
                .withReason("Unhealthy")
                .withNote("Readiness probe failed: HTTP probe failed with statuscode: 500")
                .withRegarding(new ObjectReferenceBuilder().withName("test-pod").withNamespace("default").build())
                .build();
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of(unhealthyEvent));
        assertEquals(1, problems.size());
        assertEquals("HealthCheckFailing", problems.get(0).problemType());
        assertTrue(problems.get(0).message().contains("Readiness probe failed"));
    }

    @Test
    void ignoresHealthCheckWhenNoUnhealthyEvents() {
        Pod pod = buildRunningNotReadyPod();
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertTrue(problems.isEmpty());
    }

    @Test
    void detectsUnschedulable() {
        Pod pod = buildUnschedulablePod(Instant.now().minusSeconds(300).toString());
        DetectionConfig config = new DetectionConfig();
        config.setPendingThresholdSeconds(120);
        List<PodProblem> problems = evaluator.evaluate(pod, config, List.of());
        assertEquals(1, problems.size());
        assertEquals("Unschedulable", problems.get(0).problemType());
    }

    @Test
    void ignoresUnschedulableIfPodTooYoung() {
        Pod pod = buildUnschedulablePod(Instant.now().minusSeconds(30).toString());
        DetectionConfig config = new DetectionConfig();
        config.setPendingThresholdSeconds(120);
        List<PodProblem> problems = evaluator.evaluate(pod, config, List.of());
        assertTrue(problems.isEmpty());
    }

    @Test
    void detectsImagePullBackOff() {
        Pod pod = buildPodWithWaitingReason("ImagePullBackOff", 0);
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertEquals(1, problems.size());
        assertEquals("ImagePullError", problems.get(0).problemType());
    }

    @Test
    void detectsErrImagePull() {
        Pod pod = buildPodWithWaitingReason("ErrImagePull", 0);
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertEquals(1, problems.size());
        assertEquals("ImagePullError", problems.get(0).problemType());
    }

    @Test
    void detectsOOMKilled() {
        Pod pod = buildPodWithTerminatedReason("OOMKilled");
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertEquals(1, problems.size());
        assertEquals("OOMKilled", problems.get(0).problemType());
    }

    @Test
    void detectsCreateContainerConfigError() {
        Pod pod = buildPodWithWaitingReason("CreateContainerConfigError", 0);
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertEquals(1, problems.size());
        assertEquals("CreateContainerConfigError", problems.get(0).problemType());
    }

    @Test
    void detectsInitContainerFailing() {
        Pod pod = buildPodWithFailingInitContainer("CrashLoopBackOff");
        List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig(), List.of());
        assertEquals(1, problems.size());
        assertEquals("InitContainerFailing", problems.get(0).problemType());
    }

    @Test
    void skipsDisabledDetections() {
        Pod pod = buildPodWithWaitingReason("CrashLoopBackOff", 5);
        DetectionConfig config = new DetectionConfig();
        config.setCrashLoopBackOff(false);
        assertTrue(evaluator.evaluate(pod, config, List.of()).isEmpty());
    }

    @Test
    void healthyPodReturnsNoProblems() {
        Pod pod = buildHealthyPod();
        assertTrue(evaluator.evaluate(pod, new DetectionConfig(), List.of()).isEmpty());
    }

    // --- Helpers ---

    private Pod buildPodWithWaitingReason(String reason, int restarts) {
        ContainerStatus cs = new ContainerStatusBuilder()
                .withName("app").withImage("app:latest").withRestartCount(restarts)
                .withNewState().withNewWaiting().withReason(reason).endWaiting().endState()
                .withReady(false).build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(Instant.now().minusSeconds(300).toString()).endMetadata()
                .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
    }

    private Pod buildRunningPodWithRestarts(int restarts) {
        ContainerStatus cs = new ContainerStatusBuilder()
                .withName("app").withImage("app:latest").withRestartCount(restarts)
                .withNewState().withNewRunning().endRunning().endState()
                .withReady(true).build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(Instant.now().minusSeconds(300).toString()).endMetadata()
                .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
    }

    private Pod buildRunningNotReadyPod() {
        ContainerStatus cs = new ContainerStatusBuilder()
                .withName("app").withImage("app:latest").withRestartCount(0)
                .withNewState().withNewRunning().endRunning().endState()
                .withReady(false).build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(Instant.now().minusSeconds(300).toString()).endMetadata()
                .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
    }

    private Pod buildPodWithTerminatedReason(String reason) {
        ContainerStatus cs = new ContainerStatusBuilder()
                .withName("app").withImage("app:latest").withRestartCount(3)
                .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .withNewLastState().withNewTerminated().withReason(reason).withExitCode(137).endTerminated().endLastState()
                .withReady(false).build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(Instant.now().minusSeconds(300).toString()).endMetadata()
                .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
    }

    private Pod buildPodWithFailingInitContainer(String reason) {
        ContainerStatus initCs = new ContainerStatusBuilder()
                .withName("init-db").withImage("init:latest").withRestartCount(3)
                .withNewState().withNewWaiting().withReason(reason).endWaiting().endState()
                .withReady(false).build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(Instant.now().minusSeconds(300).toString()).endMetadata()
                .withNewStatus().withInitContainerStatuses(initCs).withPhase("Pending").endStatus().build();
    }

    private Pod buildUnschedulablePod(String creationTimestamp) {
        PodCondition cond = new PodConditionBuilder()
                .withType("PodScheduled").withStatus("False").withReason("Unschedulable")
                .withMessage("0/3 nodes are available: insufficient memory").build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(creationTimestamp).endMetadata()
                .withNewStatus().withPhase("Pending").withConditions(cond).endStatus().build();
    }

    private Pod buildHealthyPod() {
        ContainerStatus cs = new ContainerStatusBuilder()
                .withName("app").withImage("app:latest").withRestartCount(0)
                .withNewState().withNewRunning().endRunning().endState()
                .withReady(true).build();
        return new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default")
                .withCreationTimestamp(Instant.now().minusSeconds(300).toString()).endMetadata()
                .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
    }
}
```

- [ ] Rodar `mvn test -Dtest=PodHealthEvaluatorTest` -- deve FALHAR (classe nao existe)

- [ ] Criar `src/main/java/io/crashnotifier/service/PodHealthEvaluator.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.DetectionConfig;
import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PodHealthEvaluator {
    private static final Logger log = LoggerFactory.getLogger(PodHealthEvaluator.class);

    /** Maps K8s waiting reasons to our problem type names */
    private static final Map<String, String> WAITING_REASON_TO_PROBLEM = Map.of(
            "CrashLoopBackOff", "CrashLoopBackOff",
            "ImagePullBackOff", "ImagePullError",
            "ErrImagePull", "ImagePullError",
            "CreateContainerConfigError", "CreateContainerConfigError"
    );

    public List<PodProblem> evaluate(Pod pod, DetectionConfig config, List<Event> podEvents) {
        List<PodProblem> problems = new ArrayList<>();
        String podName = pod.getMetadata().getName();
        String namespace = pod.getMetadata().getNamespace();
        if (pod.getStatus() == null) return problems;

        // Unschedulable (with age threshold)
        if (config.isUnschedulable() && "Pending".equals(pod.getStatus().getPhase())) {
            if (!isPodOlderThan(pod, config.getPendingThresholdSeconds())) {
                checkInitContainers(pod, config, podName, namespace, problems);
                return problems;
            }
            if (pod.getStatus().getConditions() != null) {
                for (PodCondition c : pod.getStatus().getConditions()) {
                    if ("PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())
                            && "Unschedulable".equals(c.getReason())) {
                        String msg = c.getMessage() != null ? c.getMessage() : "";
                        problems.add(new PodProblem(podName, namespace, "Unschedulable", "", "", 0, msg, "", ""));
                        return problems;
                    }
                }
            }
            checkInitContainers(pod, config, podName, namespace, problems);
            return problems;
        }

        checkInitContainers(pod, config, podName, namespace, problems);
        if (pod.getStatus().getContainerStatuses() == null) return problems;

        for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
            String cn = cs.getName(), img = cs.getImage();
            int restarts = cs.getRestartCount();

            // OOMKilled (lastState terminated)
            if (config.isOomKilled() && isOomKilled(cs)) {
                problems.add(new PodProblem(podName, namespace, "OOMKilled", cn, img, restarts,
                        "Exit code: " + cs.getLastState().getTerminated().getExitCode(), "", ""));
                continue;
            }

            if (cs.getState() == null) continue;

            // Waiting state: use map to detect known error reasons
            if (cs.getState().getWaiting() != null) {
                String reason = cs.getState().getWaiting().getReason();
                String problemType = WAITING_REASON_TO_PROBLEM.get(reason);
                if (problemType != null && isDetectionEnabled(config, problemType)) {
                    String waitMsg = cs.getState().getWaiting().getMessage() != null ? cs.getState().getWaiting().getMessage() : "";
                    problems.add(new PodProblem(podName, namespace, problemType, cn, img, restarts, waitMsg, "", ""));
                    continue;
                }
            }

            // Running state
            if (cs.getState().getRunning() != null) {
                if (restarts > config.getRestartThreshold()) {
                    problems.add(new PodProblem(podName, namespace, "RestartThreshold", cn, img, restarts, "", "", ""));
                    continue;
                }
                if (config.isHealthCheckFailing() && !Boolean.TRUE.equals(cs.getReady())) {
                    String unhealthyMsg = findUnhealthyEvent(podEvents);
                    if (unhealthyMsg != null) {
                        problems.add(new PodProblem(podName, namespace, "HealthCheckFailing", cn, img, restarts, unhealthyMsg, "", ""));
                    }
                }
            }
        }
        return problems;
    }

    /** Checks if a detection is enabled in config based on problem type */
    private boolean isDetectionEnabled(DetectionConfig config, String problemType) {
        return switch (problemType) {
            case "CrashLoopBackOff" -> config.isCrashLoopBackOff();
            case "ImagePullError" -> config.isImagePullError();
            case "CreateContainerConfigError" -> config.isCreateContainerError();
            default -> true;
        };
    }

    private boolean isOomKilled(ContainerStatus cs) {
        return cs.getLastState() != null && cs.getLastState().getTerminated() != null
                && "OOMKilled".equals(cs.getLastState().getTerminated().getReason());
    }

    private void checkInitContainers(Pod pod, DetectionConfig config, String podName, String namespace, List<PodProblem> problems) {
        if (!config.isInitContainerFailing()) return;
        if (pod.getStatus() == null || pod.getStatus().getInitContainerStatuses() == null) return;
        for (ContainerStatus cs : pod.getStatus().getInitContainerStatuses()) {
            if (cs.getState() != null && cs.getState().getWaiting() != null) {
                String reason = cs.getState().getWaiting().getReason();
                if (reason != null && !reason.isEmpty() && !"PodInitializing".equals(reason)) {
                    String msg = cs.getState().getWaiting().getMessage() != null ? cs.getState().getWaiting().getMessage() : reason;
                    problems.add(new PodProblem(podName, namespace, "InitContainerFailing", cs.getName(), cs.getImage(),
                            cs.getRestartCount(), msg, "", ""));
                }
            }
        }
    }

    private boolean isPodOlderThan(Pod pod, int thresholdSeconds) {
        String ts = pod.getMetadata().getCreationTimestamp();
        if (ts == null) return true;
        try {
            Instant created = Instant.parse(ts);
            return Duration.between(created, Instant.now()).getSeconds() > thresholdSeconds;
        } catch (Exception e) {
            return true;
        }
    }

    private String findUnhealthyEvent(List<Event> events) {
        for (Event e : events) {
            if ("Unhealthy".equals(e.getReason())) {
                return e.getNote() != null ? e.getNote() : e.getReason();
            }
        }
        return null;
    }
}
```

- [ ] Rodar `mvn test -Dtest=PodHealthEvaluatorTest` -- deve PASSAR
- [ ] Commit: `feat: add PodHealthEvaluator with detection logic and tests`

---

## Step 3.2: AlertDeduplicator + testes

- [ ] Criar `src/test/java/io/crashnotifier/service/AlertDeduplicatorTest.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.AlertedPod;
import io.crashnotifier.domain.PodProblem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertDeduplicatorTest {
    private final AlertDeduplicatorHandler deduplicator = new AlertDeduplicatorHandler();

    @Test
    void filtersAlreadyAlertedPods() {
        var problems = List.of(
                new PodProblem("pod-a", "ns", "CrashLoopBackOff", "app", "img", 5, "", "", ""),
                new PodProblem("pod-b", "ns", "CrashLoopBackOff", "app", "img", 3, "", "", ""));
        var alerted = List.of(new AlertedPod("pod-a", "CrashLoopBackOff"));
        var result = deduplicator.filterNew(problems, alerted);
        assertEquals(1, result.size());
        assertEquals("pod-b", result.get(0).podName());
    }

    @Test
    void returnsAllWhenNoneAlerted() {
        var problems = List.of(new PodProblem("pod-a", "ns", "CrashLoopBackOff", "app", "img", 5, "", "", ""));
        assertEquals(1, deduplicator.filterNew(problems, List.of()).size());
    }

    @Test
    void returnsEmptyWhenAllAlerted() {
        var problems = List.of(new PodProblem("pod-a", "ns", "CrashLoopBackOff", "app", "img", 5, "", "", ""));
        var alerted = List.of(new AlertedPod("pod-a", "CrashLoopBackOff"));
        assertTrue(deduplicator.filterNew(problems, alerted).isEmpty());
    }
}
```

- [ ] Rodar `mvn test -Dtest=AlertDeduplicatorTest` -- deve FALHAR

- [ ] Criar `src/main/java/io/crashnotifier/service/AlertDeduplicator.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.AlertedPod;
import io.crashnotifier.domain.PodProblem;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AlertDeduplicator {
    public List<PodProblem> filterNew(List<PodProblem> problems, List<AlertedPod> alertedPods) {
        Set<String> alerted = alertedPods.stream().map(AlertedPod::getPodName).collect(Collectors.toSet());
        return problems.stream().filter(p -> !alerted.contains(p.podName())).toList();
    }
}
```

- [ ] Rodar `mvn test -Dtest=AlertDeduplicatorTest` -- deve PASSAR
- [ ] Commit: `feat: add AlertDeduplicator with tests`
