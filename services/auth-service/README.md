# Authentication Service

Implemented: registration, login, signed JWT access tokens, opaque rotating refresh tokens, logout revocation, Flyway schema, and password-reset/email-verification token groundwork. Raw reset and verification tokens are deliberately not delivered: Notification Service/event integration is planned, not implemented.

Set the variables in `.env.example` (do not create or commit a real `.env`) and run `mvn -pl services/auth-service -am spring-boot:run`.

The image intentionally has no Docker `HEALTHCHECK`: the minimal Java runtime does not include an HTTP client, and `java -version` is not an application-health test. Container orchestration should probe `/actuator/health`.
