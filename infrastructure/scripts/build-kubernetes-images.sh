#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tag="${1:-local}"
services=(
  api-gateway auth-service user-service product-service inventory-service cart-service
  order-service payment-service checkout-service notification-service search-service
  recommendation-service
)

for service in "${services[@]}"; do
  docker build \
    --file "$repository_root/services/$service/Dockerfile" \
    --tag "commerce/$service:$tag" \
    "$repository_root"
done

