# operator-crash-notifier

A Kubernetes operator that watches pods in a namespace and sends webhook alerts when unhealthy pods are detected.

## How it works

The operator manages a `PodWatcher` custom resource. Each `PodWatcher` targets a namespace and defines what to detect and where to send alerts. Every 10 seconds the operator evaluates all pods in the namespace, deduplicates alerts against its own status, and POSTs a webhook for each new problem found.

## Quick start

```yaml
apiVersion: crash-notifier.io/v1
kind: PodWatcher
metadata:
  name: my-watcher
  namespace: my-namespace
spec:
  webhook:
    url: "https://hooks.example.com/notify"
  detections:
    crashLoopBackOff: true
    restartThreshold: 5
    oomKilled: true
    unschedulable: true
    imagePullError: true
    healthCheckFailing: true
    createContainerError: true
    initContainerFailing: true
    pendingThresholdSeconds: 120
  resetCron: "0 0 * * *"
```

The operator watches the namespace where the `PodWatcher` resource is created.

## Spec reference

### `spec.webhook`

| Field | Type | Required | Description |
|---|---|---|---|
| `url` | string | yes | HTTP endpoint that receives alert POSTs |
| `headers` | list | no | Extra HTTP headers sent with every request |
| `bodyTemplate` | string | no | Custom payload template. If omitted, a default JSON is sent |
| `auth` | object | no | Token refresh configuration for authenticated endpoints |

#### `spec.webhook.headers`

```yaml
headers:
  - name: Authorization
    value: "Bearer ${auth.token}"
  - name: X-Custom-Header
    value: "some-value"
```

The placeholder `${auth.token}` is replaced at runtime with the current token from `spec.webhook.auth.token`.

#### `spec.webhook.bodyTemplate`

A string template sent as the request body. Available placeholders:

| Placeholder | Description |
|---|---|
| `${podName}` | Name of the affected pod |
| `${namespace}` | Namespace of the pod |
| `${problemType}` | Detected problem (e.g. `CrashLoopBackOff`) |
| `${containerName}` | Affected container name |
| `${imageName}` | Container image |
| `${restartCount}` | Number of container restarts |
| `${message}` | Short problem description |
| `${events}` | Kubernetes events related to the pod |
| `${logs}` | Last log lines from the container |

Example:

```yaml
bodyTemplate: |
  {
    "text": "Pod ${podName} in ${namespace} is in ${problemType}.\nLogs:\n${logs}"
  }
```

#### `spec.webhook.auth`

Configures automatic token refresh before each webhook call.

| Field | Type | Description |
|---|---|---|
| `refreshUrl` | string | URL to POST for a new token |
| `refreshBody` | string | JSON body sent to the refresh endpoint. Supports `${auth.token}` and `${auth.refreshToken}` |
| `tokenJsonPath` | string | JSONPath to extract the new token from the refresh response (e.g. `$.token`) |
| `refreshTokenJsonPath` | string | JSONPath to extract the new refresh token (e.g. `$.refreshToken`) |
| `token` | string | Current access token. Updated automatically when refreshed |
| `refreshToken` | string | Current refresh token. Updated automatically when refreshed |

Example:

```yaml
auth:
  refreshUrl: "https://auth.example.com/token/refresh"
  refreshBody: '{"token": "${auth.token}", "refreshToken": "${auth.refreshToken}"}'
  tokenJsonPath: "$.token"
  refreshTokenJsonPath: "$.refreshToken"
  token: "eyJ..."
  refreshToken: "eyJ..."
```

When the token changes after a refresh, the operator patches the `PodWatcher` spec to persist the new values automatically.

---

### `spec.detections`

All fields are optional. Defaults are shown below.

| Field | Type | Default | Description |
|---|---|---|---|
| `crashLoopBackOff` | bool | `true` | Alert when a container is in CrashLoopBackOff |
| `restartThreshold` | int | `5` | Alert when restart count exceeds this value |
| `oomKilled` | bool | `true` | Alert when a container is OOMKilled |
| `unschedulable` | bool | `true` | Alert when a pod cannot be scheduled |
| `imagePullError` | bool | `true` | Alert on ImagePullBackOff or ErrImagePull |
| `healthCheckFailing` | bool | `true` | Alert on failing liveness or readiness probes |
| `createContainerError` | bool | `true` | Alert on CreateContainerConfigError |
| `initContainerFailing` | bool | `true` | Alert when an init container fails |
| `pendingThresholdSeconds` | int | `120` | Alert when a pod stays Pending longer than this |

---

### `spec.resetCron`

Cron expression that controls when the list of alerted pods is cleared, allowing the operator to re-alert on recurring problems.

| Field | Type | Default | Description |
|---|---|---|---|
| `resetCron` | string | `"0 0 * * *"` | Standard cron expression (runs daily at midnight by default) |

---

## Default webhook payload

When no `bodyTemplate` is configured, the operator sends:

```json
{
  "podName": "my-pod-abc123",
  "namespace": "my-namespace",
  "problemType": "CrashLoopBackOff",
  "containerName": "app",
  "imageName": "my-image:latest",
  "restartCount": 8,
  "message": "Back-off restarting failed container",
  "events": "...",
  "logs": "..."
}
```

## Status

The operator tracks alerted pods in the resource status to avoid duplicate alerts:

```yaml
status:
  alertedPods:
    - podName: my-pod-abc123
      reason: CrashLoopBackOff
      alertedAt: "2026-05-05T10:30:00Z"
```

This list is cleared according to `spec.resetCron`.

## Install with Helm

```bash
helm install operator-crash-notifier ./helm/operator-crash-notifier \
  --set podWatcher.webhook.url="https://hooks.example.com/notify" \
  --set podWatcher.detections.restartThreshold=3
```
