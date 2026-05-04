package io.crashnotifier.scheduler;

import io.crashnotifier.crd.PodWatcher;
import io.crashnotifier.crd.PodWatcherStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;

public class AlertResetScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertResetScheduler.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final KubernetesClient client;
    private final Map<String, String> scheduledCrons = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public AlertResetScheduler(KubernetesClient client) {
        this.client = client;
    }

    public void schedule(String cron, PodWatcher resource) {
        String key = resource.getMetadata().getNamespace() + "/" + resource.getMetadata().getName();
        String currentCron = scheduledCrons.get(key);
        if (cron.equals(currentCron)) return;

        cancel(key);
        try {
            String[] parts = cron.trim().split("\\s+");
            int minute = Integer.parseInt(parts[0]);
            int hour = Integer.parseInt(parts[1]);
            Runnable reset = () -> {
                try {
                    log.info("Resetting alerts for {}", key);
                    PodWatcher current = client.resources(PodWatcher.class)
                            .inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName()).get();
                    if (current == null) return;
                    if (current.getStatus() == null) current.setStatus(new PodWatcherStatus());
                    current.getStatus().setAlertedPods(new ArrayList<>());
                    client.resource(current).patchStatus();
                } catch (Exception e) {
                    log.error("Reset failed for {}: {}", key, e.getMessage());
                }
            };
            long delay = computeDelay(hour, minute);
            ScheduledFuture<?> task = executor.scheduleAtFixedRate(reset, delay,
                    TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
            scheduledTasks.put(key, task);
            scheduledCrons.put(key, cron);
            log.info("Daily reset scheduled at {}:{} UTC for {}", hour, minute, key);
        } catch (Exception e) {
            log.warn("Invalid cron '{}' for {}, defaulting to midnight: {}", cron, key, e.getMessage());
            schedule("0 0 * * *", resource);
        }
    }

    public void cancel(String key) {
        ScheduledFuture<?> task = scheduledTasks.remove(key);
        if (task != null) task.cancel(false);
        scheduledCrons.remove(key);
    }

    public void shutdown() {
        scheduledTasks.values().forEach(t -> t.cancel(false));
        executor.shutdown();
    }

    long computeDelay(int hour, int minute) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = now.with(LocalTime.of(hour, minute));
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Duration.between(now, next).toMillis();
    }
}
