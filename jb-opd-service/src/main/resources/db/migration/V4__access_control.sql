-- Break-glass: a doctor who is not assigned to a visit can open it in an emergency, with a reason.
-- The grant is short-lived and the reason also lands in the visit's own history.
CREATE TABLE access_grant (
    id          BIGSERIAL PRIMARY KEY,
    visit_id    BIGINT       NOT NULL REFERENCES visits (id),
    username    VARCHAR(50)  NOT NULL,
    reason      TEXT         NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_access_grant_lookup ON access_grant (visit_id, username, expires_at);

-- Who looked at which patient's clinical record, and how. Append-only.
CREATE TABLE clinical_access_log (
    id          BIGSERIAL PRIMARY KEY,
    visit_id    BIGINT       NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    action      VARCHAR(30)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_clinical_access_log_visit ON clinical_access_log (visit_id, occurred_at);
CREATE INDEX idx_clinical_access_log_user ON clinical_access_log (username, occurred_at);
