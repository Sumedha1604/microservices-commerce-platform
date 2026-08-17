# Auth Service database

Flyway migration `V1__create_auth_schema.sql` owns `users`, `refresh_tokens`, `password_reset_tokens`, and `email_verification_tokens`. All foreign keys remain within the Auth Service database. Refresh, reset, and verification tables store SHA-256 token digests, never raw tokens.
