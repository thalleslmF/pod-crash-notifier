package io.crashnotifier;

import io.crashnotifier.reconciler.PodWatcherReconciler;
import io.crashnotifier.scheduler.AlertResetScheduler;
import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class OperatorApplication {
    private static final Logger log = LoggerFactory.getLogger(OperatorApplication.class);

    public static void main(String[] args) {
        log.info("Starting Pod Crash Notifier Operator");
        Operator operator = new Operator();
        AlertResetScheduler scheduler = new AlertResetScheduler(operator.getKubernetesClient());
        operator.register(new PodWatcherReconciler(scheduler));
        operator.installShutdownHook(Duration.ofSeconds(10));
        operator.start();
        log.info("Operator started successfully");
    }
}
