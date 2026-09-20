# ADR 0009: Kustomize Kubernetes baseline and practical CI

## Status

Accepted

## Context

The platform had Dockerfiles, an E2E Compose topology, single-node KRaft Kafka overlays, and a full
Compose observability stack, but no Kubernetes resources or GitHub Actions workflow. The local
target has limited memory, so duplicating all ten PostgreSQL processes and the full logging/tracing
stack would make useful validation difficult.

## Decision

Use plain Kubernetes YAML organized as a Kustomize base plus a Docker Desktop-oriented local
overlay in the `commerce` namespace. Deploy every application with one replica, probes, requests
and limits, non-root security settings, and an internal ClusterIP Service. Expose only API Gateway,
using a LoadBalancer patch in the local overlay.

Use one PostgreSQL StatefulSet and PVC with ten separately owned databases and users. This preserves
service credentials and database boundaries while sharing a development server process. Keep Kafka
as a one-node KRaft Deployment, with ephemeral data. Deploy only Prometheus on Kubernetes; retain
the Compose stack for Grafana, Loki, and Tempo.

Use generated development secrets in the local overlay and a shared ConfigMap for Kubernetes DNS,
JDBC URLs, Kafka, health probes, and low-memory JVM settings. Continue to let applications run
Flyway at startup.

Run Java 21 reactor tests/package and Kustomize plus kubeconform validation automatically in GitHub
Actions. Keep all-service Docker builds as a manual, no-push workflow to control pull-request time
and cost.

## Consequences

Local deployment is repeatable and downstream APIs are no longer exposed around gateway security.
The full declared memory request is still substantial. A single PostgreSQL instance, ephemeral
Kafka/Prometheus, placeholder Secrets, and LoadBalancer access are development compromises. This
does not provide HA, cloud infrastructure, TLS, NetworkPolicy, autoscaling, managed secrets,
coordinated migrations, durable messaging, or full Kubernetes observability.

