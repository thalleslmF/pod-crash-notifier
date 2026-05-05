# Marco 6: Manifests + Docker

> **Entregavel:** Pronto para deploy -- manifests K8s, Dockerfile, build completo.

**Parent plan:** `2026-04-30-pod-crash-notifier.md`

**Mudancas vs plano original:**
- Removida permissao de secrets no RBAC (tokens via env var)
- Secret yaml agora e opcional (so para referencia)
- Deployment injeta token via env var

---

## Step 6.1: Kubernetes manifests

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
  - apiGroups: ["events.k8s.io"]
    resources: ["events"]
    verbs: ["get", "list"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["get", "list"]
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
          env:
            - name: FLOW_TOKEN
              valueFrom:
                secretKeyRef:
                  name: flow-token-secret
                  key: token
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
```

- [ ] Criar `k8s/secret.yaml` (referencia para o usuario criar o secret)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: flow-token-secret
type: Opaque
stringData:
  token: "YOUR-FLOW-TOKEN-HERE"
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
        value: "${FLOW_TOKEN}"
    bodyTemplate: |
      {
        "output_type": "text",
        "input_type": "text",
        "input_value": "Pod ${podName} no namespace ${namespace} com problema ${problemType}.\nContainer: ${containerName}\nImage: ${imageName}\nRestarts: ${restartCount}\nMessage: ${message}\n\nEvents:\n${events}\n\nLogs:\n${logs}"
      }
  detections:
    crashLoopBackOff: true
    restartThreshold: 5
    healthCheckFailing: true
    unschedulable: true
    imagePullError: true
    oomKilled: true
    createContainerError: true
    initContainerFailing: true
    pendingThresholdSeconds: 120
  resetCron: "0 0 * * *"
```

- [ ] Commit: `feat: add Kubernetes manifests`

---

## Step 6.2: Dockerfile + build final

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
