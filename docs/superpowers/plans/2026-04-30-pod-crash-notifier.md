# Pod Crash Notifier Operator - Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kubernetes operator in Java that watches pods for crashes/health issues and sends diagnostic data to a configurable webhook.

**Architecture:** Java Operator SDK reconciler watches Pod events via Informer, evaluates health conditions, deduplicates alerts by pod name (daily reset), and POSTs diagnostic data to a user-configured webhook with templatable body and headers.

**Tech Stack:** Java 21, Java Operator SDK 5.x, Fabric8 Kubernetes Client, Maven, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-04-30-pod-crash-notifier-design.md`

---

## Marco 1: Projeto Base

> Entregavel: projeto Maven compila, main class existe, dependencias resolvidas.

### Step 1.1: pom.xml + OperatorApplication

- [ ] Criar `pom.xml` com dependencias: JOSDK 5.x, Fabric8, Jackson, Logback, JUnit 5, Mockito, crd-generator-apt

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.crashnotifier</groupId>
    <artifactId>operator-crash-notifier</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <josdk.version>5.0.1</josdk.version>
        <fabric8.version>7.0.1</fabric8.version>
        <jackson.version>2.17.0</jackson.version>
        <junit.version>5.10.2</junit.version>
        <mockito.version>5.11.0</mockito.version>
        <slf4j.version>2.0.12</slf4j.version>
        <logback.version>1.5.3</logback.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.javaoperatorsdk</groupId>
            <artifactId>operator-framework</artifactId>
            <version>${josdk.version}</version>
        </dependency>
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>kubernetes-client</artifactId>
            <version>${fabric8.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>${slf4j.version}</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
        </dependency>
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>crd-generator-apt</artifactId>
            <version>${fabric8.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>io.crashnotifier.OperatorApplication</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] Criar `src/main/java/io/crashnotifier/OperatorApplication.java` (main class basica, sem reconciler ainda)

```java
package io.crashnotifier;

import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperatorApplication {
    private static final Logger log = LoggerFactory.getLogger(OperatorApplication.class);

    public static void main(String[] args) {
        log.info("Starting Pod Crash Notifier Operator");
        Operator operator = new Operator();
        operator.installShutdownHook();
        operator.start();
        log.info("Operator started successfully");
    }
}
```

- [ ] Criar `src/main/resources/logback.xml`

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="io.crashnotifier" level="INFO"/>
    <logger name="io.javaoperatorsdk" level="INFO"/>
    <logger name="io.fabric8" level="WARN"/>
    <root level="WARN">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

- [ ] Rodar `mvn compile` -- deve passar
- [ ] Commit: `feat: scaffold Maven project with JOSDK dependencies`

---

## Marco 2: CRD Model

> Entregavel: todas as classes do CRD compilam, CRD YAML e gerado automaticamente.

### Step 2.1: Classes de configuracao (WebhookHeader, WebhookConfig, DetectionConfig)

- [ ] Criar `src/main/java/io/crashnotifier/crd/WebhookHeader.java`

```java
package io.crashnotifier.crd;

public class WebhookHeader {
    private String name;
    private String value;
    private SecretKeyRef valueFrom;

    public static class SecretKeyRef {
        private SecretRef secretKeyRef;
        public SecretRef getSecretKeyRef() { return secretKeyRef; }
        public void setSecretKeyRef(SecretRef secretKeyRef) { this.secretKeyRef = secretKeyRef; }
    }

    public static class SecretRef {
        private String name;
        private String key;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public SecretKeyRef getValueFrom() { return valueFrom; }
    public void setValueFrom(SecretKeyRef valueFrom) { this.valueFrom = valueFrom; }
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

    public boolean isCrashLoopBackOff() { return crashLoopBackOff; }
    public void setCrashLoopBackOff(boolean v) { this.crashLoopBackOff = v; }
    public int getRestartThreshold() { return restartThreshold; }
    public void setRestartThreshold(int v) { this.restartThreshold = v; }
    public boolean isHealthCheckFailing() { return healthCheckFailing; }
    public void setHealthCheckFailing(boolean v) { this.healthCheckFailing = v; }
    public boolean isUnschedulable() { return unschedulable; }
    public void setUnschedulable(boolean v) { this.unschedulable = v; }
}
```

- [ ] Rodar `mvn compile` -- deve passar
- [ ] Commit: `feat: add webhook and detection config classes`

### Step 2.2: Spec, Status e CR (PodWatcher)

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

- [ ] Criar `PodWatcherSpec.java`, `PodWatcherStatus.java`, `PodWatcher.java`

```java
// PodWatcherSpec.java
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

```java
// PodWatcherStatus.java
package io.crashnotifier.crd;

import java.util.ArrayList;
import java.util.List;

public class PodWatcherStatus {
    private List<AlertedPod> alertedPods = new ArrayList<>();

    public List<AlertedPod> getAlertedPods() { return alertedPods; }
    public void setAlertedPods(List<AlertedPod> alertedPods) { this.alertedPods = alertedPods; }
}
```

```java
// PodWatcher.java
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

---

## Marco 3: Logica de Deteccao (com testes)

> Entregavel: PodHealthEvaluator e AlertDeduplicator funcionando e testados.

### Step 3.1: PodProblem model + PodHealthEvaluator + testes

- [ ] Criar `src/main/java/io/crashnotifier/model/PodProblem.java`

```java
package io.crashnotifier.domain;

public record PodProblem(
        String podName, String namespace, String problemType,
        String containerName, String imageName, int restartCount,
        String events, String logs
) {
}
```

- [ ] Criar `src/test/java/io/crashnotifier/service/PodHealthEvaluatorTest.java` com testes:
  - `detectsCrashLoopBackOff`
  - `detectsRestartThresholdExceeded`
  - `detectsHealthCheckFailing` (com startedAt > 60s atras)
  - `detectsUnschedulable`
  - `skipsDisabledDetections`
  - `healthyPodReturnsNoProblems`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.DetectionConfig;
import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.api.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PodHealthEvaluatorTest {

  private final PodHealthHandler evaluator = new PodHealthHandler();

  @Test
  void detectsCrashLoopBackOff() {
    Pod pod = buildPodWithWaitingReason("CrashLoopBackOff", 5);
    List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig());
    assertEquals(1, problems.size());
    assertEquals("CrashLoopBackOff", problems.get(0).problemType());
  }

  @Test
  void detectsRestartThresholdExceeded() {
    Pod pod = buildRunningPodWithRestarts(10);
    DetectionConfig config = new DetectionConfig();
    config.setRestartThreshold(5);
    List<PodProblem> problems = evaluator.evaluate(pod, config);
    assertEquals(1, problems.size());
    assertEquals("RestartThreshold", problems.get(0).problemType());
  }

  @Test
  void detectsHealthCheckFailing() {
    Pod pod = buildRunningNotReadyPod(Instant.now().minusSeconds(120).toString());
    List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig());
    assertEquals(1, problems.size());
    assertEquals("HealthCheckFailing", problems.get(0).problemType());
  }

  @Test
  void ignoresHealthCheckIfRunningLessThan60s() {
    Pod pod = buildRunningNotReadyPod(Instant.now().minusSeconds(10).toString());
    List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig());
    assertTrue(problems.isEmpty());
  }

  @Test
  void detectsUnschedulable() {
    Pod pod = buildUnschedulablePod();
    List<PodProblem> problems = evaluator.evaluate(pod, new DetectionConfig());
    assertEquals(1, problems.size());
    assertEquals("Unschedulable", problems.get(0).problemType());
  }

  @Test
  void skipsDisabledDetections() {
    Pod pod = buildPodWithWaitingReason("CrashLoopBackOff", 5);
    DetectionConfig config = new DetectionConfig();
    config.setCrashLoopBackOff(false);
    assertTrue(evaluator.evaluate(pod, config).isEmpty());
  }

  @Test
  void healthyPodReturnsNoProblems() {
    Pod pod = buildHealthyPod();
    assertTrue(evaluator.evaluate(pod, new DetectionConfig()).isEmpty());
  }

  // --- Helpers ---

  private Pod buildPodWithWaitingReason(String reason, int restarts) {
    ContainerStatus cs = new ContainerStatusBuilder()
            .withName("app").withImage("app:latest").withRestartCount(restarts)
            .withNewState().withNewWaiting().withReason(reason).endWaiting().endState()
            .withReady(false).build();
    return new PodBuilder()
            .withNewMetadata().withName("test-pod").withNamespace("default").endMetadata()
            .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
  }

  private Pod buildRunningPodWithRestarts(int restarts) {
    ContainerStatus cs = new ContainerStatusBuilder()
            .withName("app").withImage("app:latest").withRestartCount(restarts)
            .withNewState().withNewRunning().endRunning().endState()
            .withReady(true).build();
    return new PodBuilder()
            .withNewMetadata().withName("test-pod").withNamespace("default").endMetadata()
            .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
  }

  private Pod buildRunningNotReadyPod(String startedAt) {
    ContainerStatus cs = new ContainerStatusBuilder()
            .withName("app").withImage("app:latest").withRestartCount(0)
            .withNewState().withNewRunning().withStartedAt(startedAt).endRunning().endState()
            .withReady(false).build();
    return new PodBuilder()
            .withNewMetadata().withName("test-pod").withNamespace("default").endMetadata()
            .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
  }

  private Pod buildUnschedulablePod() {
    PodCondition cond = new PodConditionBuilder()
            .withType("PodScheduled").withStatus("False").withReason("Unschedulable").build();
    return new PodBuilder()
            .withNewMetadata().withName("test-pod").withNamespace("default").endMetadata()
            .withNewStatus().withPhase("Pending").withConditions(cond).endStatus().build();
  }

  private Pod buildHealthyPod() {
    ContainerStatus cs = new ContainerStatusBuilder()
            .withName("app").withImage("app:latest").withRestartCount(0)
            .withNewState().withNewRunning().endRunning().endState()
            .withReady(true).build();
    return new PodBuilder()
            .withNewMetadata().withName("test-pod").withNamespace("default").endMetadata()
            .withNewStatus().withContainerStatuses(cs).withPhase("Running").endStatus().build();
  }
}
```

- [ ] Rodar `mvn test -Dtest=PodHealthEvaluatorTest` -- deve FALHAR (classe nao existe)

- [ ] Criar `src/main/java/io/crashnotifier/service/PodHealthEvaluator.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.DetectionConfig;
import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class PodHealthEvaluator {
  private static final Logger log = LoggerFactory.getLogger(PodHealthEvaluator.class);

  public List<PodProblem> evaluate(Pod pod, DetectionConfig config) {
    List<PodProblem> problems = new ArrayList<>();
    String podName = pod.getMetadata().getName();
    String namespace = pod.getMetadata().getNamespace();
    if (pod.getStatus() == null) return problems;

    // Unschedulable
    if (config.isUnschedulable() && "Pending".equals(pod.getStatus().getPhase())) {
      if (pod.getStatus().getConditions() != null) {
        for (PodCondition c : pod.getStatus().getConditions()) {
          if ("PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())
                  && "Unschedulable".equals(c.getReason())) {
            problems.add(new PodProblem(podName, namespace, "Unschedulable", "", "", 0, "", ""));
          }
        }
      }
      return problems;
    }

    if (pod.getStatus().getContainerStatuses() == null) return problems;

    for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
      String cn = cs.getName(), img = cs.getImage();
      int restarts = cs.getRestartCount();

      // CrashLoopBackOff
      if (config.isCrashLoopBackOff() && cs.getState() != null && cs.getState().getWaiting() != null
              && "CrashLoopBackOff".equals(cs.getState().getWaiting().getReason())) {
        problems.add(new PodProblem(podName, namespace, "CrashLoopBackOff", cn, img, restarts, "", ""));
        continue;
      }

      // Restart threshold
      if (restarts > config.getRestartThreshold() && cs.getState() != null && cs.getState().getRunning() != null) {
        problems.add(new PodProblem(podName, namespace, "RestartThreshold", cn, img, restarts, "", ""));
        continue;
      }

      // Health check failing (running but not ready for > 60s)
      if (config.isHealthCheckFailing() && cs.getState() != null && cs.getState().getRunning() != null
              && !Boolean.TRUE.equals(cs.getReady()) && cs.getState().getRunning().getStartedAt() != null) {
        var startedAt = java.time.Instant.parse(cs.getState().getRunning().getStartedAt());
        if (java.time.Duration.between(startedAt, java.time.Instant.now()).getSeconds() > 60) {
          problems.add(new PodProblem(podName, namespace, "HealthCheckFailing", cn, img, restarts, "", ""));
        }
      }
    }
    return problems;
  }
}
```

- [ ] Rodar `mvn test -Dtest=PodHealthEvaluatorTest` -- deve PASSAR
- [ ] Commit: `feat: add PodHealthEvaluator with detection logic and tests`

### Step 3.2: AlertDeduplicator + testes

- [ ] Criar `src/test/java/io/crashnotifier/service/AlertDeduplicatorTest.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.AlertedPod;
import io.crashnotifier.domain.PodProblem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertDeduplicatorTest {
  private final AlertDeduplicatorHandler deduplicator = new AlertDeduplicatorHandler();

  @Test
  void filtersAlreadyAlertedPods() {
    var problems = List.of(
            new PodProblem("pod-a", "ns", "CrashLoopBackOff", "app", "img", 5, "", ""),
            new PodProblem("pod-b", "ns", "CrashLoopBackOff", "app", "img", 3, "", ""));
    var alerted = List.of(new AlertedPod("pod-a", "CrashLoopBackOff"));
    var result = deduplicator.filterNew(problems, alerted);
    assertEquals(1, result.size());
    assertEquals("pod-b", result.get(0).podName());
  }

  @Test
  void returnsAllWhenNoneAlerted() {
    var problems = List.of(new PodProblem("pod-a", "ns", "CrashLoopBackOff", "app", "img", 5, "", ""));
    assertEquals(1, deduplicator.filterNew(problems, List.of()).size());
  }

  @Test
  void returnsEmptyWhenAllAlerted() {
    var problems = List.of(new PodProblem("pod-a", "ns", "CrashLoopBackOff", "app", "img", 5, "", ""));
    var alerted = List.of(new AlertedPod("pod-a", "CrashLoopBackOff"));
    assertTrue(deduplicator.filterNew(problems, alerted).isEmpty());
  }
}
```

- [ ] Rodar `mvn test -Dtest=AlertDeduplicatorTest` -- deve FALHAR
- [ ] Criar `src/main/java/io/crashnotifier/service/AlertDeduplicator.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.AlertedPod;
import io.crashnotifier.domain.PodProblem;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AlertDeduplicator {
  public List<PodProblem> filterNew(List<PodProblem> problems, List<AlertedPod> alertedPods) {
    Set<String> alerted = alertedPods.stream().map(AlertedPod::getPodName).collect(Collectors.toSet());
    return problems.stream().filter(p -> !alerted.contains(p.podName())).toList();
  }
}
```

- [ ] Rodar `mvn test -Dtest=AlertDeduplicatorTest` -- deve PASSAR
- [ ] Commit: `feat: add AlertDeduplicator with tests`

---

## Marco 4: Servicos de Infraestrutura

> Entregavel: WebhookNotifier e PodDiagnosticCollector compilam, template resolution testado.

### Step 4.1: WebhookNotifier + testes de template

- [ ] Criar `src/test/java/io/crashnotifier/service/WebhookNotifierTest.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.domain.PodProblem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookNotifierTest {
  @Test
  void resolvesBodyTemplate() {
    var notifier = new WebhookNotifier(null);
    var problem = new PodProblem("my-pod", "prod", "CrashLoopBackOff", "app", "app:v1", 5, "ev", "lg");
    String template = "{\"input_value\": \"Pod ${podName} in ${namespace} - ${problemType}\"}";
    assertEquals("{\"input_value\": \"Pod my-pod in prod - CrashLoopBackOff\"}", notifier.resolveTemplate(template, problem));
  }

  @Test
  void resolvesAllPlaceholders() {
    var notifier = new WebhookNotifier(null);
    var problem = new PodProblem("p", "ns", "type", "c", "img", 3, "ev", "lg");
    String template = "${podName}-${namespace}-${problemType}-${containerName}-${imageName}-${restartCount}-${events}-${logs}";
    assertEquals("p-ns-type-c-img-3-ev-lg", notifier.resolveTemplate(template, problem));
  }

  @Test
  void usesDefaultPayloadWhenNoTemplate() {
    var notifier = new WebhookNotifier(null);
    var problem = new PodProblem("my-pod", "prod", "CrashLoopBackOff", "app", "app:v1", 5, "events", "logs");
    String result = notifier.buildPayload(null, problem);
    assertTrue(result.contains("\"podName\":\"my-pod\""));
    assertTrue(result.contains("\"problemType\":\"CrashLoopBackOff\""));
  }
}
```

- [ ] Rodar `mvn test -Dtest=WebhookNotifierTest` -- deve FALHAR
- [ ] Criar `src/main/java/io/crashnotifier/service/WebhookNotifier.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.crd.WebhookConfig;
import io.crashnotifier.crd.WebhookHeader;
import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WebhookNotifier {
  private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
  private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final KubernetesClient kubernetesClient;

  public WebhookNotifier(KubernetesClient kubernetesClient) {
    this.kubernetesClient = kubernetesClient;
  }

  public boolean notify(WebhookConfig config, PodProblem problem) {
    try {
      String payload = buildPayload(config.getBodyTemplate(), problem);
      var rb = HttpRequest.newBuilder()
              .uri(URI.create(config.getUrl()))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(30))
              .POST(HttpRequest.BodyPublishers.ofString(payload));
      if (config.getHeaders() != null) {
        for (WebhookHeader h : config.getHeaders()) {
          String v = resolveHeaderValue(h);
          if (v != null) rb.header(h.getName(), v);
        }
      }
      var resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        log.info("Webhook sent for pod {}/{}", problem.namespace(), problem.podName());
        return true;
      }
      log.error("Webhook status {} for {}/{}: {}", resp.statusCode(), problem.namespace(), problem.podName(), resp.body());
      return false;
    } catch (Exception e) {
      log.error("Webhook failed for {}/{}: {}", problem.namespace(), problem.podName(), e.getMessage());
      return false;
    }
  }

  String resolveTemplate(String template, PodProblem p) {
    return template
            .replace("${podName}", p.podName()).replace("${namespace}", p.namespace())
            .replace("${problemType}", p.problemType()).replace("${containerName}", p.containerName())
            .replace("${imageName}", p.imageName()).replace("${restartCount}", String.valueOf(p.restartCount()))
            .replace("${events}", p.events()).replace("${logs}", p.logs());
  }

  String buildPayload(String bodyTemplate, PodProblem p) {
    if (bodyTemplate != null && !bodyTemplate.isBlank()) return resolveTemplate(bodyTemplate, p);
    return String.format(
            "{\"podName\":\"%s\",\"namespace\":\"%s\",\"problemType\":\"%s\","
                    + "\"containerName\":\"%s\",\"imageName\":\"%s\",\"restartCount\":%d,"
                    + "\"events\":\"%s\",\"logs\":\"%s\"}",
            esc(p.podName()), esc(p.namespace()), esc(p.problemType()),
            esc(p.containerName()), esc(p.imageName()), p.restartCount(),
            esc(p.events()), esc(p.logs()));
  }

  private String resolveHeaderValue(WebhookHeader header) {
    if (header.getValue() != null) return header.getValue();
    if (header.getValueFrom() != null && header.getValueFrom().getSecretKeyRef() != null) {
      try {
        var ref = header.getValueFrom().getSecretKeyRef();
        var secret = kubernetesClient.secrets()
                .inNamespace(kubernetesClient.getNamespace()).withName(ref.getName()).get();
        if (secret != null && secret.getData() != null) {
          String enc = secret.getData().get(ref.getKey());
          if (enc != null) return new String(java.util.Base64.getDecoder().decode(enc));
        }
        log.error("Secret {} key {} not found", ref.getName(), ref.getKey());
      } catch (Exception e) {
        log.error("Secret resolve failed for {}: {}", header.getName(), e.getMessage());
      }
    }
    return null;
  }

  private String esc(String v) {
    if (v == null) return "";
    return v.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
```

- [ ] Rodar `mvn test -Dtest=WebhookNotifierTest` -- deve PASSAR
- [ ] Commit: `feat: add WebhookNotifier with template resolution and tests`

### Step 4.2: PodDiagnosticCollector

- [ ] Criar `src/main/java/io/crashnotifier/service/PodDiagnosticCollector.java`

```java
package io.crashnotifier.handler;

import io.crashnotifier.domain.PodProblem;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

public class PodDiagnosticCollector {
  private static final Logger log = LoggerFactory.getLogger(PodDiagnosticCollector.class);
  private static final int LOG_TAIL_LINES = 50;
  private final KubernetesClient client;

  public PodDiagnosticCollector(KubernetesClient client) {
    this.client = client;
  }

  public PodProblem enrich(PodProblem p) {
    String events = collectEvents(p.podName(), p.namespace());
    String logs = collectLogs(p.podName(), p.namespace(), p.containerName());
    return new PodProblem(p.podName(), p.namespace(), p.problemType(),
            p.containerName(), p.imageName(), p.restartCount(), events, logs);
  }

  private String collectEvents(String podName, String ns) {
    try {
      return client.v1().events().inNamespace(ns)
              .withField("involvedObject.name", podName).list().getItems().stream()
              .map(e -> String.format("[%s] %s: %s",
                      e.getLastTimestamp() != null ? e.getLastTimestamp() : e.getEventTime(),
                      e.getReason(), e.getMessage()))
              .collect(Collectors.joining("\n"));
    } catch (Exception e) {
      log.warn("Events failed for {}/{}: {}", ns, podName, e.getMessage());
      return "";
    }
  }

  private String collectLogs(String podName, String ns, String container) {
    if (container == null || container.isEmpty()) return "";
    try {
      return client.pods().inNamespace(ns).withName(podName)
              .inContainer(container).tailingLines(LOG_TAIL_LINES).terminated().getLog();
    } catch (Exception e) {
      try {
        return client.pods().inNamespace(ns).withName(podName)
                .inContainer(container).tailingLines(LOG_TAIL_LINES).getLog();
      } catch (Exception e2) {
        log.warn("Logs failed for {}/{}/{}: {}", ns, podName, container, e2.getMessage());
        return "";
      }
    }
  }
}
```

- [ ] Rodar `mvn compile` -- deve passar
- [ ] Commit: `feat: add PodDiagnosticCollector for events and logs`

---

## Marco 5: Reconciler + Scheduler

> Entregavel: operator funcional -- reconciler integra todos os servicos, scheduler reseta alertas diariamente.

### Step 5.1: AlertResetScheduler

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

### Step 5.2: PodWatcherReconciler + atualizar OperatorApplication

- [ ] Criar `src/main/java/io/crashnotifier/reconciler/PodWatcherReconciler.java`

```java
package io.crashnotifier.reconciler;

import io.crashnotifier.crd.*;
import io.crashnotifier.domain.PodProblem;
import io.crashnotifier.scheduler.AlertResetScheduler;
import io.crashnotifier.handler.*;
import io.fabric8.kubernetes.api.model.Pod;
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
  private final WebhookNotifier webhookNotifier;
  private final PodDiagnosticCollector diagnosticCollector;
  private final AlertResetScheduler resetScheduler;
  private final KubernetesClient client;

  public PodWatcherReconciler(KubernetesClient client) {
    this.client = client;
    this.webhookNotifier = new WebhookNotifier(client);
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
      List<PodProblem> problems = healthEvaluator.evaluate(pod, resource.getSpec().getDetections());
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

---

## Marco 6: Manifests + Docker

> Entregavel: pronto para deploy -- manifests K8s, Dockerfile, build completo.

### Step 6.1: Kubernetes manifests

- [ ] Criar `k8s/secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: flow-token-secret
type: Opaque
stringData:
  token: "YOUR-FLOW-TOKEN-HERE"
```

- [ ] Criar `k8s/rbac.yaml` (ServiceAccount + Role + RoleBinding)

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: pod-crash-notifier
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-crash-notifier
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["get", "list"]
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get"]
  - apiGroups: ["crash-notifier.io"]
    resources: ["podwatchers"]
    verbs: ["get", "list", "watch", "patch", "update"]
  - apiGroups: ["crash-notifier.io"]
    resources: ["podwatchers/status"]
    verbs: ["get", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: pod-crash-notifier
subjects:
  - kind: ServiceAccount
    name: pod-crash-notifier
roleRef:
  kind: Role
  name: pod-crash-notifier
  apiGroup: rbac.authorization.k8s.io
```

- [ ] Criar `k8s/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pod-crash-notifier
spec:
  replicas: 1
  selector:
    matchLabels:
      app: pod-crash-notifier
  template:
    metadata:
      labels:
        app: pod-crash-notifier
    spec:
      serviceAccountName: pod-crash-notifier
      containers:
        - name: operator
          image: pod-crash-notifier:latest
          imagePullPolicy: IfNotPresent
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
```

- [ ] Criar `k8s/sample-podwatcher.yaml`

```yaml
apiVersion: crash-notifier.io/v1
kind: PodWatcher
metadata:
  name: my-watcher
spec:
  webhook:
    url: "https://flow.ciandt.com/advanced-flows/api/v1/run/YOUR-FLOW-ID?version=production&stream=false"
    headers:
      - name: "FlowToken"
        valueFrom:
          secretKeyRef:
            name: flow-token-secret
            key: token
    bodyTemplate: |
      {
        "output_type": "text",
        "input_type": "text",
        "input_value": "Pod ${podName} no namespace ${namespace} com problema ${problemType}.\nContainer: ${containerName}\nImage: ${imageName}\nRestarts: ${restartCount}\n\nEvents:\n${events}\n\nLogs:\n${logs}"
      }
  detections:
    crashLoopBackOff: true
    restartThreshold: 5
    healthCheckFailing: true
    unschedulable: true
  resetCron: "0 0 * * *"
```

- [ ] Commit: `feat: add Kubernetes manifests`

### Step 6.2: Dockerfile + build final

- [ ] Criar `Dockerfile`

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] Rodar `mvn clean package` -- build final, todos os testes passam
- [ ] Commit: `feat: add Dockerfile and finalize build`
