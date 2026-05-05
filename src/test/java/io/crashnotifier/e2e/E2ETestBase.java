package io.crashnotifier.e2e;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.crashnotifier.crd.*;
import io.crashnotifier.reconciler.PodWatcherReconciler;
import io.crashnotifier.scheduler.AlertResetScheduler;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@Testcontainers
public abstract class E2ETestBase {

    private static final Logger log = LoggerFactory.getLogger(E2ETestBase.class);

    @Container
    static K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.4-k3s1"))
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("k3s")));

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    protected static KubernetesClient client;
    private static boolean crdApplied = false;
    protected KubernetesClient operatorClient;
    protected Operator operator;
    protected String testNamespace;

    @BeforeAll
    static void initClient() {
        String kubeconfig = k3s.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeconfig);
        client = new KubernetesClientBuilder().withConfig(config).build();

        // Apply CRD once
        if (!crdApplied) {
            var crdResource = E2ETestBase.class.getResourceAsStream(
                    "/META-INF/fabric8/podwatchers.crash-notifier.io-v1.yml");
            if (crdResource != null) {
                client.load(crdResource).create();
                crdApplied = true;
                // Wait for CRD to be established
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @BeforeEach
    void setUp() {
        // Create isolated namespace
        testNamespace = "e2e-" + System.currentTimeMillis();
        client.namespaces().resource(
                new NamespaceBuilder()
                        .withNewMetadata().withName(testNamespace).endMetadata().build()
        ).create();

        // Create a separate client for the operator (operator.stop() closes it)
        String kubeconfig = k3s.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeconfig);
        operatorClient = new KubernetesClientBuilder().withConfig(config).build();

        // Start operator programmatically
        AlertResetScheduler scheduler = new AlertResetScheduler(operatorClient);
        PodWatcherReconciler reconciler = new PodWatcherReconciler(scheduler);

        operator = new Operator(overrider -> overrider.withKubernetesClient(operatorClient));
        operator.register(reconciler);
        operator.start();

        // Stub WireMock
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200)));
    }

    @AfterEach
    void tearDown() {
        if (operator != null) {
            operator.stop();
        }
        if (client != null && testNamespace != null) {
            client.namespaces().withName(testNamespace).delete();
        }
    }

    /**
     * Executes kubectl inside the K3s container. Useful for debugging.
     * Example: kubectl("get", "pods", "-n", testNamespace)
     */
    protected String kubectl(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "kubectl";
            System.arraycopy(args, 0, cmd, 1, args.length);
            var result = k3s.execInContainer(cmd);
            String output = result.getStdout() + result.getStderr();
            log.debug("kubectl {}: {}", String.join(" ", args), output);
            return output;
        } catch (Exception e) {
            log.warn("kubectl exec failed: {}", e.getMessage());
            return "";
        }
    }

    protected PodWatcher createPodWatcher(DetectionConfig detections) {
        PodWatcher pw = new PodWatcher();
        pw.getMetadata().setName("test-watcher");
        pw.getMetadata().setNamespace(testNamespace);

        PodWatcherSpec spec = new PodWatcherSpec();
        WebhookConfig webhook = new WebhookConfig();
        webhook.setUrl(wireMock.baseUrl() + "/webhook");
        spec.setWebhook(webhook);
        spec.setDetections(detections != null ? detections : new DetectionConfig());
        pw.setSpec(spec);

        return client.resource(pw).inNamespace(testNamespace).create();
    }
}
