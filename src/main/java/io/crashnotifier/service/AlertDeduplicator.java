package io.crashnotifier.service;

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
