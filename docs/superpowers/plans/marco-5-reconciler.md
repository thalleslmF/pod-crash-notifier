# Marco 5: Reconciler + Scheduler

> **Entregavel:** Operator funcional -- reconciler integra todos os servicos, scheduler reseta alertas diariamente.

**Parent plan:** `2026-04-30-pod-crash-notifier.md`

**Mudancas vs plano original:**
- Reconciler busca events por pod e passa para PodHealthEvaluator
- WebhookNotifier instanciado sem KubernetesClient

---

## Step 5.1: AlertResetScheduler

- [ ] Criar `src/main/java/io/crashnotifier/scheduler/AlertResetScheduler.java`

```java
package io.crashnotifier.scheduler;

import io.crashnotifier.crd.PodWatcher;
import io.crashnotifier.crd.PodWatcherStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;

public class AlertResetScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertResetScheduler.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final KubernetesClient client;
    private ScheduledFuture<?> currentTask;

    public AlertResetScheduler(KubernetesClient client) { this.client = client; }

    public void schedule(String cron, PodWatcher resource) {
        cancel();
        try {
            String[] parts = cron.trim().split("\\s+");
            int minute = Integer.parseInt(parts[0]);
            int hour = Integer.parseInt(parts[1]);
            Runnable reset = () -> {
                try {
                    log.info("Resetting alerts for {}/{}", resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                    if (resource.getStatus() == null) resource.setStatus(new PodWatcherStatus());
                    resource.getStatus().setAlertedPods(new ArrayList<>());
                    client.resource(resource).patchStatus();
                } catch (Exception e) { log.error("Reset failed: {}", e.getMessage()); }
            };
            long delay = computeDelay(hour, minute);
            currentTask = scheduler.scheduleAtFixedRate(reset, delay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
            log.info("Daily reset at {}:{} UTC for {}", hour, minute, resource.getMetadata().getName());
        } catch (Exception e) {
            log.warn("Invalid cron '{}', defaulting to midnight: {}", cron, e.getMessage());
            schedule("0 0 * * *", resource);
        }
    }

    public void cancel() { if (currentTask != null) currentTask.cancel(false); }
    public void shutdown() { cancel(); scheduler.shutdown(); }

    long computeDelay(int hour, int minute) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = now.with(LocalTime.of(hour, minute));
        if (!next.isAfter(now)) next = next.plusDays(1);
        return java.time.Duration.between(now, next).toMillis();
    }
}
```

- [ ] Rodar `mvn compile` -- deve passar
- [ ] Commit: `feat: add AlertResetScheduler for daily status reset`

---

## Step 5.2: PodWatcherReconciler + atualizar OperatorApplication

- [ ] Criar `src/main/java/io/crashnotifier/reconciler/PodWatcherReconciler.java`

```java
package io.crashnotifier.reconciler;

import io.crashnotifier.crd.*;
import io.crashnotifier.domain.PodProblem;
import io.crashnotifier.scheduler.AlertResetScheduler;
import io.crashnotifier.handler.*;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@ControllerConfiguration
public class PodWatcherReconciler implements Reconciler<PodWatcher>, EventSourceInitializer<PodWatcher> {
    private static final Logger log = LoggerFactory.getLogger(PodWatcherReconciler.class);

    private final PodHealthHandler healthEvaluator = new PodHealthHandler();
    private final AlertDeduplicatorHandler deduplicator = new AlertDeduplicatorHandler();
    private final WebhookNotifier webhookNotifier = new WebhookNotifier();
    private final PodDiagnosticCollector diagnosticCollector;
    private final AlertResetScheduler resetScheduler;
    private final KubernetesClient client;

    public PodWatcherReconciler(KubernetesClient client) {
        this.client = client;
        this.diagnosticCollector = new PodDiagnosticCollector(client);
        this.resetScheduler = new AlertResetScheduler(client);
    }

    @Override
    public Map<String, EventSource> prepareEventSources(EventSourceContext<PodWatcher> context) {
        var podEventSource = new InformerEventSource<>(
                InformerEventSource.InformerConfiguration.from(Pod.class, context)
                        .withSecondaryToPrimaryMapper(pod -> context.getPrimaryCache().list().stream()
                                .filter(pw -> pw.getMetadata().getNamespace().equals(pod.getMetadata().getNamespace()))
                                .map(pw -> new ResourceID(pw.getMetadata().getName(), pw.getMetadata().getNamespace()))
                                .collect(Collectors.toSet()))
                        .build(),
                context);
        return Map.of("pods", podEventSource);
    }

    @Override
    public UpdateControl<PodWatcher> reconcile(PodWatcher resource, Context<PodWatcher> context) {
        String ns = resource.getMetadata().getNamespace();
        log.debug("Reconciling PodWatcher {}/{}", ns, resource.getMetadata().getName());

        if (resource.getStatus() == null) resource.setStatus(new PodWatcherStatus());
        resetScheduler.schedule(resource.getSpec().getResetCron(), resource);

        List<Pod> pods = client.pods().inNamespace(ns).list().getItems();
        List<AlertedPod> newAlerts = new ArrayList<>();

        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            List<Event> podEvents = diagnosticCollector.getEventsForPod(podName, ns);
            List<PodProblem> problems = healthEvaluator.evaluate(pod, resource.getSpec().getDetections(), podEvents);
            List<PodProblem> newProblems = deduplicator.filterNew(problems, resource.getStatus().getAlertedPods());
            for (PodProblem problem : newProblems) {
                PodProblem enriched = diagnosticCollector.enrich(problem);
                if (webhookNotifier.notify(resource.getSpec().getWebhook(), enriched)) {
                    newAlerts.add(new AlertedPod(enriched.podName(), enriched.problemType()));
                }
            }
        }

        if (!newAlerts.isEmpty()) {
            resource.getStatus().getAlertedPods().addAll(newAlerts);
            return UpdateControl.patchStatus(resource);
        }
        return UpdateControl.noUpdate();
    }
}
```

- [ ] Atualizar `OperatorApplication.java` para registrar o reconciler:

```java
package io.crashnotifier;

import io.crashnotifier.reconciler.PodWatcherReconciler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperatorApplication {
    private static final Logger log = LoggerFactory.getLogger(OperatorApplication.class);

    public static void main(String[] args) {
        log.info("Starting Pod Crash Notifier Operator");
        KubernetesClient client = new KubernetesClientBuilder().build();
        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        operator.register(new PodWatcherReconciler(client));
        operator.installShutdownHook();
        operator.start();
        log.info("Operator started successfully");
    }
}
```

- [ ] Rodar `mvn clean package` -- deve compilar e todos os testes passarem
- [ ] Commit: `feat: add PodWatcherReconciler and wire up operator`
