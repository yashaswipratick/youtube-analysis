CREATE TABLE IF NOT EXISTS creator_coach_record (
    owner_id VARCHAR(255) NOT NULL,
    record_type VARCHAR(80) NOT NULL,
    record_id VARCHAR(255) NOT NULL,
    payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, record_type, record_id)
);

CREATE INDEX IF NOT EXISTS idx_creator_coach_record_owner_type
    ON creator_coach_record(owner_id, record_type, updated_at);

CREATE TABLE IF NOT EXISTS oauth2_authorized_client (
    client_registration_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(255) NOT NULL,
    access_token_encrypted CLOB NOT NULL,
    access_token_issued_at TIMESTAMP,
    access_token_expires_at TIMESTAMP,
    access_token_scopes CLOB,
    refresh_token_encrypted CLOB,
    refresh_token_issued_at TIMESTAMP,
    PRIMARY KEY (client_registration_id, principal_name)
);
