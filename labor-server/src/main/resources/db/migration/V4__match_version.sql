ALTER TABLE hik_attendance_event
    ADD COLUMN match_version INT NOT NULL DEFAULT 0 AFTER match_reason;
