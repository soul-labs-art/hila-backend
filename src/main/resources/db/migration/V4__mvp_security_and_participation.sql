ALTER TABLE users ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 CHECK (session_version >= 0);

ALTER TABLE participation_records ADD COLUMN attendance VARCHAR(20);
UPDATE participation_records p SET attendance = CASE WHEN a.commitment_status = 'NO_SHOW'
    THEN 'NO_SHOW' ELSE 'COMPLETED' END
FROM applications a WHERE a.id = p.application_id;
ALTER TABLE participation_records ALTER COLUMN attendance SET NOT NULL,
    ADD CONSTRAINT ck_participation_attendance CHECK (attendance IN ('COMPLETED', 'NO_SHOW'));

ALTER TABLE needs ADD COLUMN cancellation_reason VARCHAR(1000);

CREATE INDEX ix_notifications_user_date ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX ix_audit_events_date ON audit_events (occurred_at DESC, id DESC);
