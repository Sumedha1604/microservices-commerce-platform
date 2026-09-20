# GitHub Actions CI

## Automatic CI

`.github/workflows/ci.yml` runs on every pull request and every push to `main`. It grants read-only
repository permission and has three independent jobs:

1. `frontend` installs Node 20.19 with npm caching and runs `npm ci`, lint, Vitest, and the Vite
   production build.
2. `build-and-test` installs Temurin Java 21 with Maven dependency caching, runs the full reactor
   with `mvn test`, then verifies packaging with `mvn package -DskipTests`.
3. `manifest-validation` renders the local Kustomize overlay with kubectl 1.33.0 and validates every
   non-secret base objects against Kubernetes schemas with kubeconform 0.7.0 in strict mode. The
   local overlay (including its generated development Secret) must also render successfully, but
   credential values are not passed into the third-party validation container.

Unit and Testcontainers integration tests run in the normal Maven reactor. GitHub-hosted Ubuntu
runners provide Docker, so PostgreSQL and Kafka Testcontainers tests can run without repository
secrets. Tests in `tests/end-to-end` target externally running services. Health probes skip
unavailable services, while checkout, Kafka, search, recommendation, and notification scenarios
are guarded by explicit opt-in system properties. CI does not start the Compose E2E platform or
silently enable those scenarios.

## Docker build validation

`.github/workflows/docker-build-validation.yml` is manual (`workflow_dispatch`) because building all
service and frontend images on every pull request is expensive and redundant with the normal builds.
It uses a matrix and the GitHub Actions build cache, never logs in, and never pushes.

Image publication is intentionally not implemented. A future release-only GHCR workflow should use
the built-in `GITHUB_TOKEN`, immutable SHA/version tags, and must never push from pull requests.

## Local equivalents

```bash
mvn test
mvn package -DskipTests
cd frontend && npm ci && npm run lint && npm test && npm run build
kubectl kustomize infrastructure/kubernetes/overlays/local > /tmp/commerce.yaml
kubectl kustomize infrastructure/kubernetes/base > /tmp/commerce-base.yaml
docker run --rm \
  -v /tmp/commerce-base.yaml:/manifests/commerce.yaml:ro \
  ghcr.io/yannh/kubeconform:v0.7.0 \
  -strict -summary -kubernetes-version 1.33.0 /manifests/commerce.yaml
```

No CI job deploys a cluster, publishes an image, or needs application secrets. Dependabot is not
enabled in this milestone to avoid adding automated update volume before the initial workflows have
established a stable runtime baseline.
