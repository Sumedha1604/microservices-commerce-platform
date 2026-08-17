CREATE TABLE users (
 user_id UUID PRIMARY KEY, email VARCHAR(320) NOT NULL UNIQUE, password_hash TEXT NOT NULL,
 role VARCHAR(32) NOT NULL, is_verified BOOLEAN NOT NULL, status VARCHAR(32) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE refresh_tokens (
 token_id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(user_id), refresh_token_hash TEXT NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL, revoked BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE TABLE password_reset_tokens (
 reset_id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(user_id), token_hash TEXT NOT NULL UNIQUE,
 expiry_time TIMESTAMPTZ NOT NULL, used BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL);
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE TABLE email_verification_tokens (
 verification_id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(user_id), token_hash TEXT NOT NULL UNIQUE,
 expiry_time TIMESTAMPTZ NOT NULL, used BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL);
CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
