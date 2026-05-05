package io.crashnotifier.e2e;

import io.crashnotifier.crd.DetectionConfig;
import io.crashnotifier.crd.PodWatcher;
import io.fabric8.kubernetes.api.model.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class PodCrashE2ETest extends E2ETestBase {

    @Test
    void detectsCrashLoopBackOff() {
        createPodWatcher(null);
        createCrashingPod("crash-pod");

        await().atMost(Duration.ofSeconds(300)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/webhook"))
                    .withRequestBody(containing("CrashLoopBackOff"))
                    .withRequestBody(containing("crash-pod")));
        });

        // Verify PodWatcher status updated
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            PodWatcher pw = client.resources(PodWatcher.class)
                    .inNamespace(testNamespace).withName("test-watcher").get();
            assertNotNull(pw.getStatus());
            assertTrue(pw.getStatus().getAlertedPods().stream()
                    .anyMatch(a -> "crash-pod".equals(a.getPodName())));
        });
    }

    @Test
    void detectsUnschedulablePod() {
        DetectionConfig config = new DetectionConfig();
        config.setPendingThresholdSeconds(10);
        createPodWatcher(config);
        createUnschedulablePod("pending-pod");

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/webhook"))
                    .withRequestBody(containing("Unschedulable"))
                    .withRequestBody(containing("pending-pod")));
        });

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            PodWatcher pw = client.resources(PodWatcher.class)
                    .inNamespace(testNamespace).withName("test-watcher").get();
            assertNotNull(pw.getStatus());
            assertTrue(pw.getStatus().getAlertedPods().stream()
                    .anyMatch(a -> "pending-pod".equals(a.getPodName())));
        });
    }

    @Test
    void detectsOOMKilled() {
        createPodWatcher(null);
        createOOMPod("oom-pod");

        await().atMost(Duration.ofSeconds(300)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/webhook"))
                    .withRequestBody(containing("OOMKilled"))
                    .withRequestBody(containing("oom-pod")));
        });

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            PodWatcher pw = client.resources(PodWatcher.class)
                    .inNamespace(testNamespace).withName("test-watcher").get();
            assertNotNull(pw.getStatus());
            assertTrue(pw.getStatus().getAlertedPods().stream()
                    .anyMatch(a -> "oom-pod".equals(a.getPodName())));
        });
    }

    private void createCrashingPod(String name) {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(testNamespace).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("app")
                .withImage("busybox:latest")
                .withCommand("sh", "-c", "echo 'app starting'; exit 1")
                .endContainer()
                .withRestartPolicy("Always")
                .endSpec()
                .build();
        client.pods().inNamespace(testNamespace).resource(pod).create();
    }

    private void createUnschedulablePod(String name) {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(testNamespace).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("app")
                .withImage("busybox:latest")
                .withCommand("sleep", "3600")
                .withNewResources()
                .addToRequests("memory", new Quantity("999Gi"))
                .endResources()
                .endContainer()
                .endSpec()
                .build();
        client.pods().inNamespace(testNamespace).resource(pod).create();
    }

    private void createOOMPod(String name) {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(testNamespace).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("app")
                .withImage("busybox:latest")
                .withCommand("sh", "-c", "echo 'allocating memory'; head -c 100m /dev/urandom > /dev/null; sleep 60")
                .withNewResources()
                .addToLimits("memory", new Quantity("10Mi"))
                .endResources()
                .endContainer()
                .withRestartPolicy("Always")
                .endSpec()
                .build();
        client.pods().inNamespace(testNamespace).resource(pod).create();
    }
}
