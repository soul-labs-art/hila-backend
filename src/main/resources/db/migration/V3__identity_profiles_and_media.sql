
CREATE TABLE spring_session (primary_id CHAR(36) NOT NULL PRIMARY KEY, session_id CHAR(36) NOT NULL UNIQUE, creation_time BIGINT NOT NULL, last_access_time BIGINT NOT NULL, max_inactive_interval INTEGER NOT NULL, expiry_time BIGINT NOT NULL, principal_name VARCHAR(100));
CREATE INDEX ix_session_expiry ON spring_session(expiry_time);
CREATE INDEX ix_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE, attribute_name VARCHAR(200) NOT NULL, attribute_bytes BYTEA NOT NULL, PRIMARY KEY(session_primary_id,attribute_name));
ALTER TABLE volunteer_profiles ADD COLUMN preferred_modality VARCHAR(20) CHECK(preferred_modality IN ('IN_PERSON','REMOTE','HYBRID')), ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE' CHECK(visibility IN ('PRIVATE','PUBLIC'));
ALTER TABLE organizations ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE' CHECK(visibility IN ('PRIVATE','PUBLIC'));
CREATE TABLE profile_images (id UUID PRIMARY KEY, user_id UUID REFERENCES volunteer_profiles(user_id) ON DELETE CASCADE, organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE, slot VARCHAR(10) NOT NULL CHECK(slot IN ('avatar','cover')), object_key VARCHAR(500) NOT NULL UNIQUE, content_type VARCHAR(50) NOT NULL, width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0), state VARCHAR(20) NOT NULL CHECK(state IN ('PENDING','ACTIVE','DELETE_PENDING')), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), CHECK(num_nonnulls(user_id,organization_id)=1));
CREATE UNIQUE INDEX uq_image_user_slot ON profile_images(user_id,slot) WHERE state='ACTIVE';
CREATE UNIQUE INDEX uq_image_org_slot ON profile_images(organization_id,slot) WHERE state='ACTIVE';
CREATE TABLE identity_rate_limits (bucket VARCHAR(150) PRIMARY KEY, attempts INTEGER NOT NULL, resets_at TIMESTAMPTZ NOT NULL);
