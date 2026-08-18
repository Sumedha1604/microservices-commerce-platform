# Microservices Commerce Platform

## Overview

The Microservices Commerce Platform is a production-oriented distributed commerce system designed to demonstrate backend engineering, microservices architecture, event-driven communication, distributed transactions, containerization, observability, and cloud-native deployment.

The platform will be developed incrementally using independently deployable services. Each implementation step will be tested and reviewed before the next step begins.

## Development Status

**Foundation in progress; Authentication Service implemented**

The repository currently contains only the initial project structure and repository-level configuration.

`services/auth-service` provides authentication-only registration, login, JWT access tokens, rotating refresh tokens, and logout. `services/user-service` provides profiles, addresses, preferences, PostgreSQL, and Flyway. `services/product-service` provides the product catalogue (products, categories, brands, images, attributes) with search, pagination, PostgreSQL, and Flyway. `services/inventory-service` tracks per-product stock (total, reserved, and derived available quantity) with reserve/release operations, PostgreSQL, and Flyway; `productId` is referenced only as a UUID, with no foreign key or cross-service database access to Product Service. Kafka, Auth/User synchronization, avatar object storage, order integration, and distributed tracing remain planned.

## Planned Architecture

The platform will follow these architectural principles:

- Independently deployable microservices
- Database per service
- No direct cross-service database access
- REST APIs for synchronous communication
- Kafka for asynchronous event-driven communication
- Saga pattern for distributed transactions
- Transactional outbox pattern for reliable event publishing
- Idempotent event consumers
- API Gateway for centralized routing and security
- Centralized logging, metrics, and distributed tracing
- Containerized deployment using Docker and Kubernetes

## Planned Technology Stack

### Backend

- Java
- Spring Boot
- Spring Cloud Gateway
- Spring Security
- Spring Data JPA
- Maven

### Data and Messaging

- PostgreSQL
- Redis
- Apache Kafka
- OpenSearch

### Frontend

- Next.js
- TypeScript
- Tailwind CSS

### Infrastructure

- Docker
- Docker Compose
- Kubernetes
- GitHub Actions

### Observability

- OpenTelemetry
- Prometheus
- Grafana
- Loki
- Jaeger

## Repository Structure

```text
microservices-commerce-platform/
├── services/
├── frontend/
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   ├── observability/
│   └── scripts/
├── docs/
│   ├── architecture/
│   ├── api/
│   ├── database/
│   ├── events/
│   └── decisions/
├── tests/
│   ├── integration/
│   ├── end-to-end/
│   └── performance/
├── .editorconfig
├── .gitattributes
├── .gitignore
├── README.md
└── CONTRIBUTING.md
