-- Refresh tokens for short-lived access tokens.
-- The raw token only ever exists in the browser's HttpOnly cookie; the database keeps its SHA-256 hash.
-- One login = one family. Every refresh rotates the token inside the family; replaying a rotated
-- token outside a short grace window revokes the whole family (likely theft).
CREATE TABLE refresh_token (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    family_id       UUID         NOT NULL,
    token_hash      CHAR(64)     NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,              -- sliding idle timeout
    session_ends_at TIMESTAMPTZ  NOT NULL,              -- absolute cap for the whole family (one shift)
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(20)  CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE', 'ADMIN')),
    user_agent      VARCHAR(255)
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_user_active ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_token_session_end ON refresh_token (session_ends_at);
