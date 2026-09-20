# Kubernetes deployment

## Scope

This is a local/development Kubernetes baseline, primarily targeting Docker Desktop Kubernetes on
macOS. It is not a production cloud design. All resources use the `commerce` namespace. Kustomize
base resources live under `infrastructure/kubernetes/base`; the deployable local configuration is
`infrastructure/kubernetes/overlays/local`.

The base contains all 12 applications, PostgreSQL, Kafka, and Prometheus. Each application has one
Deployment and one ClusterIP Service. The local overlay changes only `api-gateway` to a
LoadBalancer and generates development secrets. No downstream application is externally exposed,
which removes the direct host-port security bypass present in the E2E Compose topology.

## Prerequisites

- Docker Desktop with Kubernetes enabled, or a compatible local cluster
- `docker`, `kubectl`, and Kustomize support built into `kubectl`
- roughly 3 GiB free cluster memory for the full stack, plus image/build overhead

The declared application requests total 600 millicores and 1.875 GiB. PostgreSQL, Kafka, and
Prometheus add 300 millicores and 704 MiB, for an approximate total request of 900 millicores and
2.56 GiB. Limits are intentionally higher and are not reservations. On a constrained Docker
Desktop installation, validate statically first and deploy a bounded subset instead of assuming the
entire stack will fit.

## Build local images

All Dockerfiles use Java 21 multi-stage builds and run the application as the non-root `app` user.
Build images with names matching the local manifests:

```bash
./infrastructure/scripts/build-kubernetes-images.sh
```

An optional argument replaces the `local` tag. If a different repository is required, change the
Kustomize `images` configuration or use `kustomize edit set image` in a private overlay; do not edit
application code. Docker Desktop Kubernetes can use images in the shared Docker image store. Other
local clusters may need `kind load docker-image`, `minikube image load`, or a registry.

## Development secrets

The local overlay uses a Kustomize `secretGenerator`. Its committed literals are conspicuous,
development-only placeholders. Replace them in a private, untracked overlay before using shared or
sensitive environments. Never put real JWT keys or database passwords in Git.

`AUTH_JWT_SECRET` is injected only into Auth Service and API Gateway. Each application receives only
its own database password. PostgreSQL receives all database passwords so its first-start init script
can create the ten database/user pairs. The generated Secret name is stable because workloads refer
to it directly.

## Render and deploy

Static rendering does not require a cluster:

```bash
kubectl kustomize infrastructure/kubernetes/overlays/local > /tmp/commerce.yaml
kubectl apply --dry-run=client -f /tmp/commerce.yaml
```

Deploy and inspect:

```bash
kubectl apply -k infrastructure/kubernetes/overlays/local
kubectl get pods,deployments,statefulsets,services -n commerce
kubectl rollout status statefulset/postgres -n commerce --timeout=5m
kubectl rollout status deployment/api-gateway -n commerce --timeout=5m
```

The first application startup runs its existing Flyway migrations. Local replicas remain at one.
Before scaling write services, introduce explicit migration coordination: concurrent application
startup and Flyway locking need an operational rollout policy.

## Access

API Gateway is the only application ingress boundary. On Docker Desktop, find its external address:

```bash
kubectl get service api-gateway -n commerce
curl http://localhost:8080/actuator/health
```

If the local LoadBalancer does not map to localhost, use the displayed external IP or temporarily
run `kubectl port-forward -n commerce service/api-gateway 8080:8080`. Do not expose downstream
services merely for convenience.

Prometheus remains internal and can be inspected with:

```bash
kubectl port-forward -n commerce service/prometheus 9090:9090
```

## Service discovery and configuration

`commerce-config` provides non-sensitive environment configuration. Gateway and Checkout URLs use
Kubernetes Service DNS, for example `http://product-service:8083`. Kafka clients use `kafka:9092`.
All JDBC URLs use `postgres:5432` with a distinct database and login per service. There is no
`localhost` or Docker Compose hostname in Kubernetes application configuration.

The Kubernetes observability subset is Prometheus only. Every application remains scrapeable at
`/actuator/prometheus`. OTLP export is disabled in this overlay because Tempo is not deployed;
Compose remains the supported local environment for the full Prometheus/Grafana/Loki/Tempo stack.

## Health and rollouts

Spring Boot probe support is enabled through configuration. Application startup probes and
readiness probes call `/actuator/health`; for database-backed services this includes database
health, so they do not become ready while PostgreSQL is unavailable. Liveness calls
`/actuator/health/liveness`, keeping dependency outages from causing restart loops. Startup probes
allow up to 150 seconds for images, Flyway, and dependencies to initialize.

Application Deployments use rolling updates with `maxUnavailable: 0` and `maxSurge: 1`. With one
replica this helps ordering but is not a zero-downtime guarantee; database migrations and local
resource pressure can still interrupt service.

## Data and messaging

One PostgreSQL 17 StatefulSet conserves local memory while preserving database and credential
isolation. A 2 GiB `ReadWriteOnce` claim uses the cluster's default StorageClass. This is neither HA
nor physical server isolation. The init script only runs on a new data directory.

Kafka is the same single-node Apache Kafka 3.9.1 KRaft approach as Compose. It has no ZooKeeper,
uses replication factor one, disables automatic topic creation, and relies on existing Spring
KafkaAdmin topic declarations. Broker data uses `emptyDir`, deliberately trading persistence for a
small demo footprint; topics and events disappear when the pod is replaced.

## Low-memory validation

Static validation is always expected. For a bounded runtime smoke test, render the full overlay,
then create a temporary overlay that includes PostgreSQL plus one database-backed service, or scale
unneeded application Deployments and Kafka/Prometheus to zero after applying. Do not call the full
platform validated unless every pod was actually observed ready. Kafka flows require the broker and
the relevant producer/consumers together.

## Removal

```bash
kubectl delete -k infrastructure/kubernetes/overlays/local
```

The StatefulSet PVC may remain depending on cluster behavior. Inspect with
`kubectl get pvc -n commerce`; delete it explicitly only when loss of local database data is
intended.

## Production gaps

This baseline has no managed Kubernetes/cloud deployment, HA PostgreSQL, HA Kafka, external secret
manager, TLS/cert-manager, mTLS/service mesh, NetworkPolicy, HPA, PodDisruptionBudget, or production
backup/restore. Storage depends on the cluster's default StorageClass. Kafka is ephemeral.
Prometheus is ephemeral and Grafana/Loki/Tempo are omitted. The shared JWT secret must be rotated in
a coordinated way if gateway/auth replicas are added. Flyway startup assumes low replica counts.
Production requires migration coordination, durable managed data services, resource/load testing,
network segmentation, secret rotation, TLS, monitoring retention, alerting, and disaster recovery.

