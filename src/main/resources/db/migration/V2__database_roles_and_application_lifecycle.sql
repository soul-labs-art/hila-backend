CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(24) NOT NULL CHECK (scope IN ('PLATFORM', 'ORGANIZATION')),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (scope, code),
    UNIQUE (id, scope)
);

INSERT INTO roles (scope, code, name, description) VALUES
    ('PLATFORM', 'USER', 'Usuario', 'Cuenta activa de Hila.'),
    ('PLATFORM', 'ADMIN', 'Administración Hila', 'Gestiona revisiones y operación de la plataforma.'),
    ('ORGANIZATION', 'ORG_OWNER', 'Responsable', 'Administra una organización y sus integrantes.'),
    ('ORGANIZATION', 'ORG_COORDINATOR', 'Coordinación', 'Gestiona causas, necesidades y postulaciones.');

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL,
    role_scope VARCHAR(24) NOT NULL DEFAULT 'PLATFORM' CHECK (role_scope = 'PLATFORM'),
    granted_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (role_id, role_scope) REFERENCES roles(id, scope) ON DELETE RESTRICT
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id, user_id);

INSERT INTO user_roles (user_id, role_id, granted_at)
SELECT u.id, r.id, u.created_at
FROM users u
JOIN roles r ON r.scope = 'PLATFORM' AND r.code = u.platform_role;

ALTER TABLE organization_memberships
    ADD COLUMN role_scope VARCHAR(24) NOT NULL DEFAULT 'ORGANIZATION'
        CHECK (role_scope = 'ORGANIZATION'),
    ADD COLUMN role_id UUID;

UPDATE organization_memberships m
SET role_id = r.id
FROM roles r
WHERE r.scope = 'ORGANIZATION'
  AND r.code = CASE m.membership_role
      WHEN 'OWNER' THEN 'ORG_OWNER'
      WHEN 'COORDINATOR' THEN 'ORG_COORDINATOR'
  END;

ALTER TABLE organization_memberships
    ALTER COLUMN role_id SET NOT NULL,
    ADD CONSTRAINT fk_organization_memberships_role
        FOREIGN KEY (role_id, role_scope) REFERENCES roles(id, scope) ON DELETE RESTRICT,
    DROP COLUMN membership_role;

ALTER TABLE users DROP COLUMN platform_role;

ALTER TABLE applications
    ADD COLUMN commitment_status VARCHAR(24),
    ADD COLUMN confirmed_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(1000);

UPDATE applications a
SET commitment_status = x.status,
    confirmed_by = x.confirmed_by,
    confirmed_at = x.confirmed_at,
    finished_at = x.finished_at,
    cancellation_reason = x.cancellation_reason
FROM assignments x
WHERE x.application_id = a.id;

UPDATE applications
SET commitment_status = 'NOT_CONFIRMED'
WHERE commitment_status IS NULL;

ALTER TABLE applications
    ALTER COLUMN commitment_status SET DEFAULT 'NOT_CONFIRMED',
    ALTER COLUMN commitment_status SET NOT NULL,
    ADD CONSTRAINT ck_applications_commitment_status
        CHECK (commitment_status IN ('NOT_CONFIRMED', 'CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    ADD CONSTRAINT ck_applications_confirmation
        CHECK ((commitment_status = 'NOT_CONFIRMED') = (confirmed_at IS NULL)),
    ADD CONSTRAINT ck_applications_confirmation_actor
        CHECK ((commitment_status = 'NOT_CONFIRMED') = (confirmed_by IS NULL));
CREATE INDEX ix_applications_commitment ON applications (need_id, commitment_status);

ALTER TABLE participation_records ADD COLUMN application_id UUID;
UPDATE participation_records p
SET application_id = a.application_id
FROM assignments a
WHERE a.id = p.assignment_id;
ALTER TABLE participation_records
    ALTER COLUMN application_id SET NOT NULL,
    ADD CONSTRAINT uq_participation_records_application UNIQUE (application_id),
    ADD CONSTRAINT fk_participation_records_application
        FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE RESTRICT,
    DROP COLUMN assignment_id;

ALTER TABLE feedback ADD COLUMN application_id UUID;
UPDATE feedback f
SET application_id = a.application_id
FROM assignments a
WHERE a.id = f.assignment_id;
ALTER TABLE feedback
    ALTER COLUMN application_id SET NOT NULL,
    ADD CONSTRAINT fk_feedback_application
        FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_feedback_application_author UNIQUE (application_id, author_user_id),
    DROP COLUMN assignment_id;
CREATE INDEX ix_feedback_application ON feedback (application_id, created_at);

DROP TABLE assignments;

ALTER TABLE needs
    ADD COLUMN outcome_status VARCHAR(20),
    ADD COLUMN outcome_summary VARCHAR(2000),
    ADD COLUMN outcome_verified_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN outcome_verified_at TIMESTAMPTZ;

UPDATE needs n
SET outcome_status = o.outcome_status,
    outcome_summary = o.summary,
    outcome_verified_by = o.verified_by,
    outcome_verified_at = o.verified_at
FROM need_outcomes o
WHERE o.need_id = n.id;

ALTER TABLE needs
    ADD CONSTRAINT ck_needs_outcome_status
        CHECK (outcome_status IS NULL OR outcome_status IN ('FULFILLED', 'PARTIAL', 'NOT_FULFILLED')),
    ADD CONSTRAINT ck_needs_outcome_fields
        CHECK (num_nonnulls(outcome_status, outcome_summary, outcome_verified_by, outcome_verified_at) IN (0, 4));
CREATE INDEX ix_needs_outcome_status ON needs (outcome_status) WHERE outcome_status IS NOT NULL;

ALTER TABLE evidence_files ADD COLUMN need_id UUID;
UPDATE evidence_files f
SET need_id = o.need_id
FROM need_outcomes o
WHERE f.need_outcome_id = o.id;
ALTER TABLE evidence_files
    ADD CONSTRAINT fk_evidence_files_need
        FOREIGN KEY (need_id) REFERENCES needs(id) ON DELETE CASCADE,
    DROP COLUMN need_outcome_id,
    ADD CONSTRAINT ck_evidence_files_one_target
        CHECK (num_nonnulls(need_id, participation_record_id) = 1);

DROP TABLE need_outcomes;
