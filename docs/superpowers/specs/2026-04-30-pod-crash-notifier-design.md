# Pod Crash Notifier Operator - Design Spec

## Overview

Kubernetes operator (Java 21 + Java Operator SDK + Maven) that watches pods in its namespace for health issues, collects diagnostic data (events, logs), sends it to an LLM API for root cause analysis, and the LLM notifies Google Chat.

## CRD: PodWatcher

```yaml
apiVersion: crash-notifier.io/v1
kind: PodWatcher
metadata:
  name: my-watcher
  namespace: production
spec:
  webhook:
    url: "https://flow.ciandt.com/advanced-flows/api/v1/run/<flow-id>?version=production&stream=false"
    headers:                          # user-defined headers
      - name: "FlowToken"
        valueFrom:
          secretKeyRef:
            name: flow-token-secret
            key: token
      - name: "X-Custom-Header"       # plain text header example
        value: "my-value"
    bodyTemplate: |                   # optional, uses placeholders; if omitted, sends default JSON
      {
        "output_type": "text",
        "input_type": "text",
        "input_value": "Pod ${podName} no namespace ${namespace} com problema ${problemType}.\nContainer: ${containerName}\nImage: ${imageName}\nRestarts: ${restartCount}\n\nEvents:\n${events}\n\nLogs:\n${logs}"
      }
  detections:
    crashLoopBackOff: true       # default: true
    restartThreshold: 5          # alert if restarts > N, default: 5
    healthCheckFailing: true     # default: true
    unschedulable: true          # Pending pods with no schedulable nodes, default: true
  resetCron: "0 0 * * *"        # cron for daily alert reset, default: midnight UTC
status:
  alertedPods:
    - podName: "my-app-abc123"
      reason: "CrashLoopBackOff"
      alertedAt: "2026-04-30T10:00:00Z"
```

**Scope:** The operator monitors pods in the same namespace where the `PodWatcher` CR is created. To monitor multiple namespaces, deploy a `PodWatcher` in each.

**Alert dedup:** One alert per pod name. Once a pod is alerted, it won't be alerted again until the daily reset (configured via `resetCron`).

**Headers:** Each header can be a plain `value` or a `valueFrom.secretKeyRef` to read from a Kubernetes Secret. This keeps the webhook config fully generic -- the operator has no knowledge of what API is on the other side.

## Detections

| Detection | Condition | Data Collected |
|---|---|---|
| CrashLoopBackOff | `containerStatuses[].waiting.reason == "CrashLoopBackOff"` | Pod events + container logs (last 50 lines) |
| Restart threshold | `containerStatuses[].restartCount > spec.detections.restartThreshold` | Pod events + container logs (last 50 lines) |
| Health check failing | Container running but `ready == false` for > 60s | Pod events + container logs (last 50 lines) |
| Unschedulable | `phase == Pending` + condition `PodScheduled == False` with reason `Unschedulable` | Pod events (no logs available) |

## Architecture

```
Pod state change (via Informer)
        |
        v
PodWatcherReconciler
   - EventSource<Pod> filtered to CR's namespace
   - On pod status change, triggers reconciliation
        |
        v
PodHealthEvaluator
   - Evaluates pod against enabled detections
   - Collects: pod metadata, container statuses, events (Events API), logs (Logs API)
   - Returns list of detected problems with diagnostic data
        |
        v
AlertDeduplicator
   - Checks status.alertedPods on the CR
   - Filters out already-alerted pod names
   - Returns only new problems
        |
        v
WebhookNotifier
   - Builds structured JSON payload with all diagnostic data
   - POST to spec.webhook.url with configured headers
   - On success: updates status.alertedPods on the CR
        |
        v
Daily Reset (via resetCron)
   - Clears status.alertedPods
   - Allows re-alerting if problems persist
```

## Components

### PodWatcherReconciler
- Primary reconciler registered with Java Operator SDK
- Registers an `InformerEventSource<Pod>` scoped to the CR's namespace
- On reconciliation: iterates watched pods, evaluates health, dedup, notify
- Manages the daily reset timer based on `spec.resetCron`

### PodHealthEvaluator
- Stateless service, receives a `Pod` object and detection config
- Returns `List<PodProblem>` where `PodProblem` contains: podName, namespace, problemType, containerName, imageName, restartCount, events, logs
- Collects events via `CoreV1Api.listNamespacedEvent` filtered by pod
- Collects logs via `CoreV1Api.readNamespacedPodLog` (last 50 lines, previous terminated container)

### AlertDeduplicator
- Receives `List<PodProblem>` and current `status.alertedPods`
- Returns filtered list excluding already-alerted pods

### WebhookNotifier
- Generic REST client, agnostic to what API is on the other side
- Resolves `spec.webhook.bodyTemplate` by replacing placeholders with diagnostic data
- Available placeholders: `${podName}`, `${namespace}`, `${problemType}`, `${containerName}`, `${imageName}`, `${restartCount}`, `${events}`, `${logs}`
- If no `bodyTemplate` is defined, uses default JSON payload with all fields
- Default payload:

```json
{
  "podName": "my-app-abc123",
  "namespace": "production",
  "problemType": "CrashLoopBackOff",
  "containerName": "my-app",
  "imageName": "my-app:v1.2.3",
  "restartCount": 12,
  "events": ["Back-off restarting failed container (2min ago)", "..."],
  "logs": "Exception in thread \"main\" java.lang.OutOfMemoryError..."
}
```

- HTTP POST to `spec.webhook.url`:
  - Header: `Content-Type: application/json` (always)
  - Additional headers from `spec.webhook.headers` (resolved from Secrets if needed)
- On HTTP 2xx: adds entry to `status.alertedPods`
- On failure: logs error, does not mark as alerted (will retry on next reconciliation)

## RBAC

The operator's ServiceAccount needs:
- `pods`: get, list, watch (namespaced)
- `pods/log`: get (namespaced)
- `events`: get, list (namespaced)
- `secrets`: get (namespaced, for FlowToken)
- `podwatchers.crash-notifier.io`: get, list, watch, patch, update (namespaced, for status updates)

## Project Structure

```
operator-crash-notifier/
  pom.xml
  src/main/java/io/crashnotifier/
    OperatorApplication.java              # Main class, starts JOSDK operator
    crd/
      PodWatcherSpec.java                 # Spec POJO
      PodWatcherStatus.java               # Status POJO
      PodWatcher.java                     # CR class
      AlertedPod.java                     # Status entry
      DetectionConfig.java                # Detection settings
      WebhookConfig.java                   # Webhook URL + headers config
    reconciler/
      PodWatcherReconciler.java           # Main reconciler
    service/
      PodHealthEvaluator.java             # Health evaluation logic
      AlertDeduplicator.java              # Dedup logic
      WebhookNotifier.java               # Generic REST webhook client
      PodDiagnosticCollector.java         # Collects events + logs
    model/
      PodProblem.java                     # Problem record
    scheduler/
      AlertResetScheduler.java            # Cron-based status reset
  src/main/resources/
    META-INF/
      fabric8/
        podwatchers.crash-notifier.io-v1.yml  # CRD YAML (auto-generated)
  src/test/java/io/crashnotifier/
    reconciler/
      PodWatcherReconcilerTest.java
    service/
      PodHealthEvaluatorTest.java
      AlertDeduplicatorTest.java
      WebhookNotifierTest.java
  k8s/
    sample-podwatcher.yaml                # Sample CR
    rbac.yaml                             # RBAC manifests
    deployment.yaml                       # Operator deployment
    secret.yaml                           # FlowToken secret template
  Dockerfile
```

## Dependencies (Maven)

- `io.javaoperatorsdk:operator-framework` (JOSDK 5.x)
- `io.fabric8:kubernetes-client` (comes with JOSDK)
- `java.net.http.HttpClient` (JDK 21 built-in, for LLM API calls)
- `com.fasterxml.jackson.core:jackson-databind` (JSON serialization)
- JUnit 5 + Mockito for tests

## Error Handling

- **Webhook failure:** Log error, do not mark pod as alerted. Next reconciliation retries.
- **Logs unavailable:** Send payload without logs field (e.g., Pending pods).
- **Events unavailable:** Send payload without events field.
- **Secret not found (for header):** Log error on reconciliation, set condition on CR status.
- **Invalid cron:** Use default midnight UTC, log warning.

## Deployment

- Dockerfile: multi-stage build (Maven build + JRE 21 runtime)
- Deployed as a Deployment with 1 replica
- ServiceAccount with RBAC per namespace where PodWatcher CRs exist
