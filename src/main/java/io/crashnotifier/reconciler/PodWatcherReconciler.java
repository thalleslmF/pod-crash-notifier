package io.crashnotifier.reconciler;

import io.crashnotifier.crd.AlertedPod;
import io.crashnotifier.crd.PodWatcher;
import io.crashnotifier.crd.PodWatcherSpec;
import io.crashnotifier.crd.PodWatcherStatus;
import io.crashnotifier.domain.PodProblem;
import io.crashnotifier.scheduler.AlertResetScheduler;
import io.crashnotifier.service.AlertDeduplicator;
import io.crashnotifier.service.PodDiagnosticCollector;
import io.crashnotifier.service.PodHealthEvaluator;
import io.crashnotifier.service.WebhookNotifier;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.SecondaryToPrimaryMapper;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PodWatcherReconciler implements Reconciler<PodWatcher> {
    private static final Logger log = LoggerFactory.getLogger(PodWatcherReconciler.class);

    private final PodHealthEvaluator evaluator = new PodHealthEvaluator();
    private final AlertDeduplicator deduplicator = new AlertDeduplicator();
    private final WebhookNotifier notifier = new WebhookNotifier();
    private final AlertResetScheduler scheduler;
    private PodDiagnosticCollector diagnosticCollector;

    public PodWatcherReconciler(AlertResetScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public List<EventSource<?, PodWatcher>> prepareEventSources(EventSourceContext<PodWatcher> context) {
        SecondaryToPrimaryMapper<Pod> podToPodWatcher = pod -> {
            // Map every pod event to all PodWatcher resources in the same namespace
            var client = context.getClient();
            return client.resources(PodWatcher.class)
                    .inNamespace(pod.getMetadata().getNamespace())
                    .list().getItems().stream()
                    .map(ResourceID::fromResource)
                    .collect(Collectors.toSet());
        };

        var config = InformerEventSourceConfiguration
                .from(Pod.class, PodWatcher.class)
                .withSecondaryToPrimaryMapper(podToPodWatcher)
                .withNamespacesInheritedFromController()
                .build();

        return List.of(new InformerEventSource<>(config, context));
    }

    @Override
    public UpdateControl<PodWatcher> reconcile(PodWatcher resource, Context<PodWatcher> context) {
        KubernetesClient client = context.getClient();
        if (diagnosticCollector == null) {
            diagnosticCollector = new PodDiagnosticCollector(client);
        }

        PodWatcherSpec spec = resource.getSpec();
        String namespace = resource.getMetadata().getNamespace();
        log.info("Reconciling PodWatcher {}/{}", namespace, resource.getMetadata().getName());

        // Schedule alert reset idempotently
        String resetCron = spec.getResetCron() != null ? spec.getResetCron() : "0 0 * * *";
        scheduler.schedule(resetCron, resource);

        // Initialize status if needed
        if (resource.getStatus() == null) {
            resource.setStatus(new PodWatcherStatus());
        }

        // List all pods in namespace
        List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();

        // Evaluate each pod
        List<PodProblem> allProblems = new ArrayList<>();
        for (Pod pod : pods) {

            List<Event> events = diagnosticCollector.getEventsForPod(
                    pod.getMetadata().getName(), namespace);
            List<PodProblem> problems = evaluator.evaluate(pod, spec.getDetections(), events);
            allProblems.addAll(problems);
        }

        // Deduplicate
        List<PodProblem> newProblems = deduplicator.filterNew(
                allProblems, resource.getStatus().getAlertedPods());

        if (newProblems.isEmpty()) {
            log.debug("No new problems for PodWatcher {}/{}", namespace, resource.getMetadata().getName());
            return UpdateControl.noUpdate();
        }

        log.info("Found {} new problems for PodWatcher {}/{}", newProblems.size(),
                namespace, resource.getMetadata().getName());

        // Enrich, notify, and track
        List<AlertedPod> newAlerted = new ArrayList<>();
        for (PodProblem problem : newProblems) {
            String podPhase = pods.stream()
                    .filter(p -> p.getMetadata().getName().equals(problem.podName()))
                    .map(p -> p.getStatus() != null ? p.getStatus().getPhase() : "")
                    .findFirst().orElse("");
            PodProblem enriched = diagnosticCollector.enrich(problem, podPhase);
            if (spec.getWebhook() != null && spec.getWebhook().getUrl() != null) {
                notifier.notify(spec.getWebhook(), enriched);
            }
            newAlerted.add(new AlertedPod(enriched.podName(), enriched.problemType()));
        }

        // Update status with new alerted pods
        List<AlertedPod> allAlerted = new ArrayList<>(resource.getStatus().getAlertedPods());
        allAlerted.addAll(newAlerted);
        resource.getStatus().setAlertedPods(allAlerted);

        return UpdateControl.patchStatus(resource);
    }
}
