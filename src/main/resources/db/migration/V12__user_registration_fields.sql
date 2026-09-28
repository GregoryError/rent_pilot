-- V12: fields for email verification and password reset (foundation for future)

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS email_verification_token VARCHAR(100),
    ADD COLUMN IF NOT EXISTS email_verification_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(100),
    ADD COLUMN IF NOT EXISTS password_reset_expires_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email
    ON users(email) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_verification_token
    ON users(email_verification_token) WHERE email_verification_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_password_reset_token
    ON users(password_reset_token) WHERE password_reset_token IS NOT NULL;

-- Add agreed_to_pd flag (для юридического follow-up)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS agreed_to_pd_at TIMESTAMP;
