CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(140) NOT NULL,
    platform_role VARCHAR(20) NOT NULL DEFAULT 'USER'
        CHECK (platform_role IN ('USER', 'ADMIN')),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_EMAIL'
        CHECK (status IN ('PENDING_EMAIL', 'ACTIVE', 'SUSPENDED')),
    adult_confirmed_at TIMESTAMPTZ NOT NULL,
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE INDEX ix_users_status ON users (status);

CREATE TABLE consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consent_type VARCHAR(40) NOT NULL,
    document_version VARCHAR(40) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    UNIQUE (user_id, consent_type, document_version)
);

CREATE TABLE account_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_account_tokens_user_purpose ON account_tokens (user_id, purpose);

CREATE TABLE volunteer_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    biography VARCHAR(1200),
    profession VARCHAR(140),
    education_level VARCHAR(80),
    email_opportunity_alerts BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE territories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(140) NOT NULL,
    territory_type VARCHAR(24) NOT NULL
        CHECK (territory_type IN ('VALLEY', 'MUNICIPALITY', 'COMMUNE', 'NEIGHBORHOOD')),
    parent_id UUID REFERENCES territories(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX ix_territories_parent ON territories (parent_id);

CREATE TABLE skills (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(140) NOT NULL,
    category VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE resources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(140) NOT NULL,
    category VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE volunteer_skills (
    volunteer_id UUID NOT NULL REFERENCES volunteer_profiles(user_id) ON DELETE CASCADE,
    skill_id UUID NOT NULL REFERENCES skills(id) ON DELETE RESTRICT,
    proficiency VARCHAR(20) CHECK (proficiency IN ('BASIC', 'INTERMEDIATE', 'ADVANCED')),
    years_experience SMALLINT CHECK (years_experience IS NULL OR years_experience >= 0),
    PRIMARY KEY (volunteer_id, skill_id)
);

CREATE TABLE volunteer_availability (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    volunteer_id UUID NOT NULL REFERENCES volunteer_profiles(user_id) ON DELETE CASCADE,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    CHECK (starts_at < ends_at),
    UNIQUE (volunteer_id, weekday, starts_at, ends_at)
);

CREATE TABLE volunteer_service_areas (
    volunteer_id UUID NOT NULL REFERENCES volunteer_profiles(user_id) ON DELETE CASCADE,
    territory_id UUID NOT NULL REFERENCES territories(id) ON DELETE RESTRICT,
    PRIMARY KEY (volunteer_id, territory_id)
);

CREATE TABLE volunteer_resources (
    volunteer_id UUID NOT NULL REFERENCES volunteer_profiles(user_id) ON DELETE CASCADE,
    resource_id UUID NOT NULL REFERENCES resources(id) ON DELETE RESTRICT,
    details VARCHAR(280),
    PRIMARY KEY (volunteer_id, resource_id)
);

CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(180) NOT NULL,
    organization_type VARCHAR(20) NOT NULL CHECK (organization_type IN ('FORMAL', 'COLLECTIVE')),
    legal_identifier VARCHAR(40),
    description VARCHAR(2000),
    verification_context VARCHAR(2000),
    public_email VARCHAR(320),
    public_website VARCHAR(500),
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    status_reason VARCHAR(1000),
    service_territory_id UUID REFERENCES territories(id) ON DELETE RESTRICT,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (organization_type <> 'COLLECTIVE' OR verification_context IS NOT NULL)
);
CREATE INDEX ix_organizations_verification_status ON organizations (verification_status, created_at);
CREATE INDEX ix_organizations_name ON organizations (lower(name));

CREATE TABLE organization_memberships (
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    membership_role VARCHAR(20) NOT NULL CHECK (membership_role IN ('OWNER', 'COORDINATOR')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX ix_organization_memberships_user ON organization_memberships (user_id, status);

CREATE TABLE organization_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reviewed_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'SUSPENDED', 'MORE_INFO')),
    reason VARCHAR(1200),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_organization_verifications_org_date ON organization_verifications (organization_id, decided_at DESC);

CREATE TABLE causes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    title VARCHAR(180) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_causes_org_status ON causes (organization_id, status);

CREATE TABLE needs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cause_id UUID NOT NULL REFERENCES causes(id) ON DELETE RESTRICT,
    title VARCHAR(180) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    expected_outcome VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED')),
    modality VARCHAR(20) NOT NULL CHECK (modality IN ('IN_PERSON', 'REMOTE', 'HYBRID')),
    risk_level VARCHAR(20) NOT NULL CHECK (risk_level IN ('LOW', 'MODERATE')),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'America/Bogota',
    territory_id UUID REFERENCES territories(id) ON DELETE RESTRICT,
    public_location_label VARCHAR(180),
    private_location_instructions VARCHAR(1000),
    conditions TEXT,
    slots_required SMALLINT NOT NULL CHECK (slots_required > 0),
    published_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (starts_at < ends_at),
    CHECK (modality = 'REMOTE' OR territory_id IS NOT NULL),
    CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX ix_needs_catalog ON needs (status, starts_at, territory_id);
CREATE INDEX ix_needs_cause ON needs (cause_id, status);

CREATE TABLE need_skills (
    need_id UUID NOT NULL REFERENCES needs(id) ON DELETE CASCADE,
    skill_id UUID NOT NULL REFERENCES skills(id) ON DELETE RESTRICT,
    required BOOLEAN NOT NULL DEFAULT true,
    minimum_proficiency VARCHAR(20) CHECK (minimum_proficiency IN ('BASIC', 'INTERMEDIATE', 'ADVANCED')),
    PRIMARY KEY (need_id, skill_id)
);

CREATE TABLE need_resources (
    need_id UUID NOT NULL REFERENCES needs(id) ON DELETE CASCADE,
    resource_id UUID NOT NULL REFERENCES resources(id) ON DELETE RESTRICT,
    required BOOLEAN NOT NULL DEFAULT false,
    details VARCHAR(280),
    PRIMARY KEY (need_id, resource_id)
);

CREATE TABLE applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    need_id UUID NOT NULL REFERENCES needs(id) ON DELETE RESTRICT,
    volunteer_id UUID NOT NULL REFERENCES volunteer_profiles(user_id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
        CHECK (status IN ('APPLIED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    message VARCHAR(1200),
    decision_reason VARCHAR(1000),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    UNIQUE (need_id, volunteer_id)
);
CREATE INDEX ix_applications_need_status ON applications (need_id, status, applied_at);
CREATE INDEX ix_applications_volunteer_date ON applications (volunteer_id, applied_at DESC);

CREATE TABLE assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL UNIQUE REFERENCES applications(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    confirmed_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1000)
);
CREATE INDEX ix_assignments_status ON assignments (status, confirmed_at);

CREATE TABLE participation_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL UNIQUE REFERENCES assignments(id) ON DELETE RESTRICT,
    actual_hours NUMERIC(5,2) CHECK (actual_hours IS NULL OR actual_hours BETWEEN 0 AND 999.99),
    participation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (participation_status IN ('PENDING', 'VERIFIED', 'DISPUTED')),
    notes VARCHAR(1200),
    recorded_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    verified_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    CHECK ((participation_status = 'VERIFIED') = (verified_at IS NOT NULL))
);

CREATE TABLE need_outcomes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    need_id UUID NOT NULL UNIQUE REFERENCES needs(id) ON DELETE RESTRICT,
    outcome_status VARCHAR(20) NOT NULL CHECK (outcome_status IN ('FULFILLED', 'PARTIAL', 'NOT_FULFILLED')),
    summary VARCHAR(2000) NOT NULL,
    verified_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evidence_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    need_outcome_id UUID REFERENCES need_outcomes(id) ON DELETE CASCADE,
    participation_record_id UUID REFERENCES participation_records(id) ON DELETE CASCADE,
    object_key VARCHAR(700) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    uploaded_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(need_outcome_id, participation_record_id) = 1)
);

CREATE TABLE feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    target_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    target_organization_id UUID REFERENCES organizations(id) ON DELETE RESTRICT,
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(1200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(target_user_id, target_organization_id) = 1),
    UNIQUE (assignment_id, author_user_id)
);
CREATE INDEX ix_feedback_assignment ON feedback (assignment_id, created_at);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(60) NOT NULL,
    title VARCHAR(180) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    resource_type VARCHAR(60),
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ
);
CREATE INDEX ix_notifications_unread ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;

CREATE TABLE email_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_email VARCHAR(320) NOT NULL,
    template_key VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_outbox_pending ON email_outbox (available_at, created_at) WHERE status = 'PENDING';

CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id, occurred_at DESC);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_user_id, occurred_at DESC);

WITH valley AS (
    INSERT INTO territories (slug, name, territory_type)
    VALUES ('valle-de-aburra', 'Valle de Aburrá', 'VALLEY')
    RETURNING id
)
INSERT INTO territories (slug, name, territory_type, parent_id)
SELECT municipalities.slug, municipalities.name, 'MUNICIPALITY', valley.id
FROM valley
CROSS JOIN (VALUES
    ('barbosa', 'Barbosa'),
    ('girardota', 'Girardota'),
    ('copacabana', 'Copacabana'),
    ('bello', 'Bello'),
    ('medellin', 'Medellín'),
    ('itagui', 'Itagüí'),
    ('envigado', 'Envigado'),
    ('sabaneta', 'Sabaneta'),
    ('la-estrella', 'La Estrella'),
    ('caldas', 'Caldas')
) AS municipalities(slug, name);
