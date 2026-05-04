package io.crashnotifier.service;

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

            if (config.isOomKilled() && isOomKilled(cs)) {
                problems.add(new PodProblem(podName, namespace, "OOMKilled", cn, img, restarts,
                        "Exit code: " + cs.getLastState().getTerminated().getExitCode(), "", ""));
                continue;
            }

            if (cs.getState() == null) continue;

            if (cs.getState().getWaiting() != null) {
                String reason = cs.getState().getWaiting().getReason();
                String problemType = WAITING_REASON_TO_PROBLEM.get(reason);
                if (problemType != null && isDetectionEnabled(config, problemType)) {
                    String waitMsg = cs.getState().getWaiting().getMessage() != null ? cs.getState().getWaiting().getMessage() : "";
                    problems.add(new PodProblem(podName, namespace, problemType, cn, img, restarts, waitMsg, "", ""));
                    continue;
                }
            }

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
