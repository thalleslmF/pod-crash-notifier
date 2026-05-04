package io.crashnotifier.service;

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
