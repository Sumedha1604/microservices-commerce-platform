# User Service

Implements user-owned profiles, addresses, and preferences with PostgreSQL and Flyway. The Auth UUID is an external identifier only; this service never reads the Auth database.

Defaults: language `en`, currency `USD`, marketing emails disabled, and order notifications enabled. `/actuator/health` is the orchestration health probe. Planned: Kafka events, Auth/User synchronization, avatar object storage, and distributed tracing.
