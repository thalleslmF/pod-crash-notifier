package io.crashnotifier;

import io.crashnotifier.reconciler.PodWatcherReconciler;
import io.crashnotifier.scheduler.AlertResetScheduler;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.ControllerConfigurationOverrider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;

public class OperatorApplication {
    private static final Logger log = LoggerFactory.getLogger(OperatorApplication.class);

    public static void main(String[] args) {
        log.info("Starting Pod Crash Notifier Operator");

        String watchNamespace = System.getenv("WATCH_NAMESPACE");

        Operator operator = new Operator();
        AlertResetScheduler scheduler = new AlertResetScheduler(operator.getKubernetesClient());
        PodWatcherReconciler reconciler = new PodWatcherReconciler(scheduler);

        if (watchNamespace != null && !watchNamespace.isBlank()) {
            log.info("Watching namespace: {}", watchNamespace);
            operator.register(reconciler, o -> o.settingNamespaces(Set.of(watchNamespace)));
        } else {
            log.info("Watching all namespaces (cluster-wide)");
            operator.register(reconciler);
        }

        operator.installShutdownHook(Duration.ofSeconds(10));
        operator.start();
        log.info("Operator started successfully");
    }
}
