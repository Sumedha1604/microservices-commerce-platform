# Contributing

## Branch Naming

Use feature branches for all work.

Examples:

- feature/auth-service
- feature/product-service
- feature/order-service

## Commit Messages

Follow Conventional Commits.

Examples:

- feat(auth): implement JWT authentication
- fix(order): resolve saga compensation issue
- chore: update documentation

## Pull Requests

Every pull request should:

- Be focused on one feature
- Include testing evidence
- Update documentation if necessary
- Avoid unrelated refactoring

## Architecture Rules

- Every microservice owns its own database.
- Services communicate through REST APIs or Kafka events.
- Direct cross-service database access is prohibited.
- Keep services independently deployable.