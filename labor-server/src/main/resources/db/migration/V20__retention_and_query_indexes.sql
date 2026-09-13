ALTER TABLE integration_call_log
    ADD KEY idx_call_log_retention (success, created_at, id),
    ADD KEY idx_call_log_project_filter_created
        (project_id, integration_type, operation_type, success, created_at, id);

ALTER TABLE hik_attendance_event
    ADD COLUMN raw_payload_archived BOOLEAN NOT NULL DEFAULT FALSE AFTER raw_payload_encrypted,
    ADD KEY idx_hik_event_raw_retention
        (raw_payload_archived, attendance_source, match_status, event_time, id),
    ADD KEY idx_hik_event_project_person_time
        (pro_code, matched_person_id, attendance_source, event_time, id);
