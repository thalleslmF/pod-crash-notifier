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
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "null";
        if (pod.getStatus() == null) {
            log.debug("Pod {}/{} has no status, skipping evaluation", namespace, podName);
            return problems;
        }

        log.debug("Evaluating pod {}/{} phase={}", namespace, podName, phase);

        if (config.isUnschedulable() && "Pending".equals(phase)) {
            if (!isPodOlderThan(pod, config.getPendingThresholdSeconds())) {
                log.debug("Pod {}/{} is Pending but not yet older than {}s threshold, skipping Unschedulable check",
                        namespace, podName, config.getPendingThresholdSeconds());
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
        if (pod.getStatus().getContainerStatuses() == null) {
            log.debug("Pod {}/{} has no container statuses (phase={})", namespace, podName, phase);
            return problems;
        }

        for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
            String cn = cs.getName(), img = cs.getImage();
            int restarts = cs.getRestartCount();
            String stateDesc = describeState(cs);
            log.debug("Pod {}/{} container={} restarts={} state={}", namespace, podName, cn, restarts, stateDesc);

            if (config.isOomKilled() && isOomKilled(cs)) {
                log.info("Pod {}/{} container={}: OOMKilled detected (exitCode={})",
                        namespace, podName, cn, cs.getLastState().getTerminated().getExitCode());
                problems.add(new PodProblem(podName, namespace, "OOMKilled", cn, img, restarts,
                        "Exit code: " + cs.getLastState().getTerminated().getExitCode(), "", ""));
                continue;
            }

            if (cs.getState() == null) {
                log.debug("Pod {}/{} container={}: state is null, skipping", namespace, podName, cn);
                continue;
            }

            if (cs.getState().getWaiting() != null) {
                String reason = cs.getState().getWaiting().getReason();
                String problemType = WAITING_REASON_TO_PROBLEM.get(reason);
                if (problemType == null) {
                    log.debug("Pod {}/{} container={}: waiting reason='{}' not a known problem, skipping",
                            namespace, podName, cn, reason);
                } else if (!isDetectionEnabled(config, problemType)) {
                    log.debug("Pod {}/{} container={}: detection for '{}' is disabled, skipping",
                            namespace, podName, cn, problemType);
                } else {
                    String waitMsg = cs.getState().getWaiting().getMessage() != null ? cs.getState().getWaiting().getMessage() : "";
                    log.info("Pod {}/{} container={}: problem detected type={} reason={} msg={}",
                            namespace, podName, cn, problemType, reason, waitMsg);
                    problems.add(new PodProblem(podName, namespace, problemType, cn, img, restarts, waitMsg, "", ""));
                    continue;
                }
            }

            if (cs.getState().getRunning() != null) {
                if (restarts > config.getRestartThreshold()) {
                    log.info("Pod {}/{} container={}: RestartThreshold exceeded (restarts={} > threshold={})",
                            namespace, podName, cn, restarts, config.getRestartThreshold());
                    problems.add(new PodProblem(podName, namespace, "RestartThreshold", cn, img, restarts, "", "", ""));
                    continue;
                } else {
                    log.debug("Pod {}/{} container={}: running, restarts={} <= threshold={}, checking health",
                            namespace, podName, cn, restarts, config.getRestartThreshold());
                }
                if (config.isHealthCheckFailing() && !Boolean.TRUE.equals(cs.getReady())) {
                    String unhealthyMsg = findUnhealthyEvent(podEvents);
                    if (unhealthyMsg != null) {
                        log.info("Pod {}/{} container={}: HealthCheckFailing detected msg={}",
                                namespace, podName, cn, unhealthyMsg);
                        problems.add(new PodProblem(podName, namespace, "HealthCheckFailing", cn, img, restarts, unhealthyMsg, "", ""));
                    } else {
                        log.debug("Pod {}/{} container={}: not ready but no Unhealthy event found",
                                namespace, podName, cn);
                    }
                }
            }

            if (cs.getState().getTerminated() != null) {
                log.debug("Pod {}/{} container={}: terminated reason={}, no specific detection matched",
                        namespace, podName, cn, cs.getState().getTerminated().getReason());
            }
        }
        if (problems.isEmpty()) {
            log.debug("Pod {}/{} evaluated: no problems detected", namespace, podName);
        } else {
            log.info("Pod {}/{} evaluated: {} problem(s) found: {}", namespace, podName, problems.size(),
                    problems.stream().map(PodProblem::problemType).toList());
        }
        return problems;
    }

    private String describeState(ContainerStatus cs) {
        if (cs.getState() == null) return "null";
        if (cs.getState().getRunning() != null) return "Running";
        if (cs.getState().getWaiting() != null) return "Waiting(" + cs.getState().getWaiting().getReason() + ")";
        if (cs.getState().getTerminated() != null) return "Terminated(" + cs.getState().getTerminated().getReason() + ")";
        return "unknown";
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
