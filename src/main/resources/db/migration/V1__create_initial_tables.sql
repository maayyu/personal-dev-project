-- PostgreSQL 17 / Flyway V1
-- Phase 5 DB design, Wave 0-A and Wave 1 decisions (see DB design section 10.2).
-- Future JPA mappings: GenerationType.SEQUENCE, allocationSize=1.

CREATE SEQUENCE users_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE memos_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE organizations_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE ai_processes_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE memo_organizations_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE users (
    id BIGINT NOT NULL DEFAULT nextval('users_id_seq'),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);
ALTER SEQUENCE users_id_seq OWNED BY users.id;

CREATE TABLE memos (
    id BIGINT NOT NULL DEFAULT nextval('memos_id_seq'),
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    content_updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_memos PRIMARY KEY (id),
    CONSTRAINT fk_memos_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_memos_content_nonblank CHECK (content ~ '[^[:space:]]')
);
ALTER SEQUENCE memos_id_seq OWNED BY memos.id;
CREATE INDEX idx_memos_user_fk ON memos (user_id);

CREATE TABLE organizations (
    id BIGINT NOT NULL DEFAULT nextval('organizations_id_seq'),
    user_id BIGINT NOT NULL,
    parent_id BIGINT,
    name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT fk_organizations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_organizations_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_organizations_parent_owner FOREIGN KEY (parent_id, user_id) REFERENCES organizations (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_organizations_sibling_name UNIQUE NULLS NOT DISTINCT (user_id, parent_id, normalized_name),
    CONSTRAINT chk_organizations_parent_not_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT chk_organizations_name_nonblank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT chk_organizations_normalized_name_nonblank CHECK (normalized_name ~ '[^[:space:]]')
);
ALTER SEQUENCE organizations_id_seq OWNED BY organizations.id;
CREATE INDEX idx_organizations_parent_fk ON organizations (parent_id);

CREATE TABLE ai_processes (
    id BIGINT NOT NULL DEFAULT nextval('ai_processes_id_seq'),
    memo_id BIGINT,
    process_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    proposed_result JSONB,
    proposed_at TIMESTAMPTZ(6),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ(6),
    completed_at TIMESTAMPTZ(6),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_ai_processes PRIMARY KEY (id),
    CONSTRAINT fk_ai_processes_memo FOREIGN KEY (memo_id) REFERENCES memos (id) ON DELETE SET NULL,
    CONSTRAINT chk_ai_processes_type CHECK (process_type IN ('INITIAL', 'REORGANIZE')),
    CONSTRAINT chk_ai_processes_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_ai_processes_proposed_at_scope CHECK (
        proposed_at IS NULL OR (process_type = 'REORGANIZE' AND status = 'COMPLETED')
    ),
    CONSTRAINT chk_ai_processes_proposal_payload CHECK (
        proposed_result IS NULL OR (proposed_at IS NOT NULL AND jsonb_typeof(proposed_result) = 'object')
    )
);
ALTER SEQUENCE ai_processes_id_seq OWNED BY ai_processes.id;
CREATE UNIQUE INDEX uq_ai_processes_reorganize_processing ON ai_processes (memo_id)
    WHERE process_type = 'REORGANIZE' AND status = 'PROCESSING';
CREATE INDEX idx_ai_processes_proposal ON ai_processes (memo_id, process_type, status, proposed_at, id)
    WHERE proposed_result IS NOT NULL;
CREATE INDEX idx_ai_processes_processing_recovery ON ai_processes (memo_id, created_at)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_ai_processes_memo_fk ON ai_processes (memo_id);

CREATE TABLE memo_organizations (
    id BIGINT NOT NULL DEFAULT nextval('memo_organizations_id_seq'),
    memo_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    relation_type VARCHAR(20) NOT NULL,
    ai_process_id BIGINT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_memo_organizations PRIMARY KEY (id),
    CONSTRAINT fk_memo_organizations_memo FOREIGN KEY (memo_id) REFERENCES memos (id) ON DELETE CASCADE,
    CONSTRAINT fk_memo_organizations_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_memo_organizations_ai_process FOREIGN KEY (ai_process_id) REFERENCES ai_processes (id) ON DELETE RESTRICT,
    CONSTRAINT uq_memo_organizations_assignment UNIQUE (memo_id, organization_id, relation_type),
    CONSTRAINT chk_memo_organizations_relation_type CHECK (relation_type IN ('AI_SUGGESTED', 'CURRENT')),
    CONSTRAINT chk_memo_organizations_ai_suggested_source CHECK (relation_type <> 'AI_SUGGESTED' OR ai_process_id IS NOT NULL)
);
ALTER SEQUENCE memo_organizations_id_seq OWNED BY memo_organizations.id;
CREATE INDEX idx_memo_organizations_organization_fk ON memo_organizations (organization_id);
