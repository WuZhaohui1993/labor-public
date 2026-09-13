ALTER TABLE project_sync_setting
    ADD COLUMN push_time_text VARCHAR(5) NULL AFTER push_enabled;

UPDATE project_sync_setting
SET push_time_text = DATE_FORMAT(push_time, '%H:%i');

ALTER TABLE project_sync_setting
    DROP COLUMN push_time,
    CHANGE COLUMN push_time_text push_time VARCHAR(5) NOT NULL DEFAULT '20:00';
