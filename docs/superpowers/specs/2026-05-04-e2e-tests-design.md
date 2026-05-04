# E2E Tests Design Spec

> Pod Crash Notifier Operator — testes end-to-end

## Objetivo

Validar o comportamento real do operator contra um cluster Kubernetes, seguindo o padrão do JOSDK (`LocallyRunOperatorExtension` in-process + cluster real).

## Abordagem

- **Cluster:** K3s via Testcontainers (auto-provisionado, CI-friendly)
- **Operator:** roda in-process via `LocallyRunOperatorExtension` do JOSDK
- **Webhook:** WireMock rodando no JVM do teste, porta dinâmica, `localhost:{port}`
- **Assertions:** Awaitility para polling (recursos K8s são eventualmente consistentes)

## Dependências (test scope)

- `org.wiremock:wiremock-standalone` — servidor HTTP fake para capturar webhooks
- `org.testcontainers:k3s` — cluster K3s efêmero
- `org.awaitility:awaitility` — polling assertions
- `io.javaoperatorsdk:operator-framework-junit-5-extension` — `LocallyRunOperatorExtension`

## Estrutura

```
src/test/java/io/crashnotifier/e2e/
├── E2ETestBase.java            // setup: k3s, kubernetesClient, wiremock, operator extension
└── PodCrashE2ETest.java        // 3 cenários
```

## E2ETestBase

Setup compartilhado:

1. `@Testcontainers` sobe K3s container (static, compartilhado entre testes)
2. Cria `KubernetesClient` com kubeconfig do K3s
3. `LocallyRunOperatorExtension` registra `PodWatcherReconciler` com o client
4. `WireMockExtension` sobe na porta dinâmica
5. Antes de cada teste: cria namespace isolado + PodWatcher CR com `webhook.url = http://localhost:{port}/webhook`
6. Após cada teste: deleta namespace (cleanup)

## Cenários

### 1. CrashLoopBackOff

- Cria pod com container que printa antes de morrer (ex: `busybox` com command `["sh", "-c", "echo 'app starting'; exit 1"]`)
- Aguarda (awaitility) até container entrar em `CrashLoopBackOff` (restarts > 0)
- Asserta:
  - WireMock recebeu POST em `/webhook`
  - Body contém `"problemType":"CrashLoopBackOff"` e o nome do pod
  - Body contém campo `events` não vazio (events do K8s como BackOff, Started, etc.)
  - Body contém campo `logs` com `"app starting"` (logs do container terminated)
  - PodWatcher `status.alertedPods` contém o pod
- Asserta dedup: reseta WireMock, espera 30s, verifica que NÃO recebeu novo POST

### 2. Unschedulable (Pending)

- Cria pod com resource request impossível (ex: `memory: 999Gi`)
- `pendingThresholdSeconds` configurado para valor baixo (ex: 10s) no DetectionConfig
- Aguarda até `pendingThresholdSeconds` passar
- Asserta:
  - WireMock recebeu POST com `"problemType":"Unschedulable"`
  - PodWatcher status atualizado

### 3. OOMKilled

- Cria pod com memory limit baixo (ex: `10Mi`) executando algo que consome memória (ex: `stress` ou alocação em shell)
- Aguarda até container ser OOMKilled e reiniciar
- Asserta:
  - WireMock recebeu POST com `"problemType":"OOMKilled"`
  - PodWatcher status atualizado

## Validações no WireMock

```java
wireMock.verify(postRequestedFor(urlEqualTo("/webhook"))
    .withRequestBody(containsString("CrashLoopBackOff"))
    .withRequestBody(containsString("test-crash-pod")));
```

## Timeouts

- K3s startup: até 120s
- Pod state change: até 120s (CrashLoopBackOff pode demorar com backoff exponencial)
- Pending threshold: configurado baixo (10s) nos testes
- OOMKill: até 60s

## Execução

- `mvn test` roda só os testes unitários (rápidos)
- `mvn verify -Pe2e` roda os e2e (profile Maven separado, precisa Docker)
- Ou via `./run.sh e2e`

## Limitações (MVP)

- K3s single-node: não testa cenários multi-node
- Operator in-process: não valida Dockerfile/deploy containerizado
- WireMock local: não testa conectividade de rede intra-cluster
