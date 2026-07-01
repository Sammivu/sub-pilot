-- V7: Notification provider tracking
--
-- NotificationLog (co.subpilot.notification.entity.NotificationLog) reads
-- and writes a `provider` and `provider_message_id` column that V5's
-- original notification_logs table never defined. Without this migration,
-- Hibernate's schema validation (spring.jpa.hibernate.ddl-auto=validate)
-- fails at startup with "missing column" errors the first time anyone
-- actually tries to send a notification.
--
-- `provider` lets the table support more than one email provider down the
-- line without a schema change; `provider_message_id` lets a specific send
-- be traced back to Brevo's own dashboard/logs for debugging.

ALTER TABLE notification_logs
    ADD COLUMN provider VARCHAR(50) NOT NULL DEFAULT 'brevo',
    ADD COLUMN provider_message_id VARCHAR(255);

CREATE INDEX idx_notification_logs_provider_message_id ON notification_logs(provider_message_id);