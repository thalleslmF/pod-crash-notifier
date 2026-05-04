package io.crashnotifier.service;

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

    private final PodHealthEvaluator evaluator = new PodHealthEvaluator();

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
