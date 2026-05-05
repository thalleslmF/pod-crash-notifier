# Marco 2: CRD Model

> **Entregavel:** Todas as classes do CRD compilam, CRD YAML gerado automaticamente.

**Parent plan:** `2026-04-30-pod-crash-notifier.md`

---

## Step 2.1: Classes de configuracao (WebhookHeader, WebhookConfig, DetectionConfig)

- [ ] Criar `src/main/java/io/crashnotifier/crd/WebhookHeader.java`

```java
package io.crashnotifier.crd;

public class WebhookHeader {
    private String name;
    private String value;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
```

- [ ] Criar `src/main/java/io/crashnotifier/crd/WebhookConfig.java`

```java
package io.crashnotifier.crd;

import java.util.List;

public class WebhookConfig {
    private String url;
    private List<WebhookHeader> headers;
    private String bodyTemplate;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<WebhookHeader> getHeaders() { return headers; }
    public void setHeaders(List<WebhookHeader> headers) { this.headers = headers; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
}
```

- [ ] Criar `src/main/java/io/crashnotifier/crd/DetectionConfig.java`

```java
package io.crashnotifier.crd;

public class DetectionConfig {
    private boolean crashLoopBackOff = true;
    private int restartThreshold = 5;
    private boolean healthCheckFailing = true;
    private boolean unschedulable = true;
    private boolean imagePullError = true;
    private boolean oomKilled = true;
    private boolean createContainerError = true;
    private boolean initContainerFailing = true;
    private int pendingThresholdSeconds = 120;

    public boolean isCrashLoopBackOff() { return crashLoopBackOff; }
    public void setCrashLoopBackOff(boolean v) { this.crashLoopBackOff = v; }
    public int getRestartThreshold() { return restartThreshold; }
    public void setRestartThreshold(int v) { this.restartThreshold = v; }
    public boolean isHealthCheckFailing() { return healthCheckFailing; }
    public void setHealthCheckFailing(boolean v) { this.healthCheckFailing = v; }
    public boolean isUnschedulable() { return unschedulable; }
    public void setUnschedulable(boolean v) { this.unschedulable = v; }
    public boolean isImagePullError() { return imagePullError; }
    public void setImagePullError(boolean v) { this.imagePullError = v; }
    public boolean isOomKilled() { return oomKilled; }
    public void setOomKilled(boolean v) { this.oomKilled = v; }
    public boolean isCreateContainerError() { return createContainerError; }
    public void setCreateContainerError(boolean v) { this.createContainerError = v; }
    public boolean isInitContainerFailing() { return initContainerFailing; }
    public void setInitContainerFailing(boolean v) { this.initContainerFailing = v; }
    public int getPendingThresholdSeconds() { return pendingThresholdSeconds; }
    public void setPendingThresholdSeconds(int v) { this.pendingThresholdSeconds = v; }
}
```

- [ ] Rodar `mvn compile` -- deve passar
- [ ] Commit: `feat: add webhook and detection config classes`

---

## Step 2.2: Spec, Status e CR (PodWatcher)

- [ ] Criar `src/main/java/io/crashnotifier/crd/AlertedPod.java`

```java
package io.crashnotifier.crd;

import java.time.Instant;

public class AlertedPod {
    private String podName;
    private String reason;
    private String alertedAt;

    public AlertedPod() {}
    public AlertedPod(String podName, String reason) {
        this.podName = podName;
        this.reason = reason;
        this.alertedAt = Instant.now().toString();
    }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getAlertedAt() { return alertedAt; }
    public void setAlertedAt(String alertedAt) { this.alertedAt = alertedAt; }
}
```

- [ ] Criar `PodWatcherSpec.java`

```java
package io.crashnotifier.crd;

public class PodWatcherSpec {
    private WebhookConfig webhook;
    private DetectionConfig detections = new DetectionConfig();
    private String resetCron = "0 0 * * *";

    public WebhookConfig getWebhook() { return webhook; }
    public void setWebhook(WebhookConfig webhook) { this.webhook = webhook; }
    public DetectionConfig getDetections() { return detections; }
    public void setDetections(DetectionConfig detections) { this.detections = detections; }
    public String getResetCron() { return resetCron; }
    public void setResetCron(String resetCron) { this.resetCron = resetCron; }
}
```

- [ ] Criar `PodWatcherStatus.java`

```java
package io.crashnotifier.crd;

import java.util.ArrayList;
import java.util.List;

public class PodWatcherStatus {
    private List<AlertedPod> alertedPods = new ArrayList<>();

    public List<AlertedPod> getAlertedPods() { return alertedPods; }
    public void setAlertedPods(List<AlertedPod> alertedPods) { this.alertedPods = alertedPods; }
}
```

- [ ] Criar `PodWatcher.java`

```java
package io.crashnotifier.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.fabric8.kubernetes.model.annotation.ShortNames;

@Group("crash-notifier.io")
@Version("v1")
@ShortNames("pw")
public class PodWatcher extends CustomResource<PodWatcherSpec, PodWatcherStatus> implements Namespaced {
}
```

- [ ] Rodar `mvn compile` -- deve gerar CRD YAML em `target/classes/META-INF/fabric8/`
- [ ] Commit: `feat: add PodWatcher CRD model classes`
