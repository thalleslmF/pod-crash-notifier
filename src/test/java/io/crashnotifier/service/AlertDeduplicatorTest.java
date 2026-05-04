package io.crashnotifier.service;

import io.crashnotifier.crd.AlertedPod;
import io.crashnotifier.domain.PodProblem;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AlertDeduplicatorTest {
    private final AlertDeduplicator deduplicator = new AlertDeduplicator();

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
