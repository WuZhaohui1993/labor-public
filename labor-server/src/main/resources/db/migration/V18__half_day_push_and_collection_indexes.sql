ALTER TABLE project_sync_setting
    ADD COLUMN last_push_slot_at TIMESTAMP(3) NULL AFTER last_push_date;

ALTER TABLE push_task
    ADD COLUMN business_at TIMESTAMP(3) NULL AFTER business_date,
    ADD KEY idx_push_task_attendance_window
        (pro_code, replay_job_id, task_type, status, business_at, next_retry_at, id),
    ADD KEY idx_push_task_project_created
        (pro_code, replay_job_id, created_at, id);

UPDATE push_task
SET business_at = created_at
WHERE business_at IS NULL;

UPDATE push_task task
JOIN hik_attendance_event event_row ON event_row.id = CAST(task.aggregate_id AS UNSIGNED)
SET task.business_at = event_row.event_time
WHERE task.task_type = 'ATTENDANCE';

ALTER TABLE push_task
    MODIFY COLUMN business_at TIMESTAMP(3) NOT NULL;

ALTER TABLE hik_attendance_event
    ADD KEY idx_hik_event_project_time (pro_code, event_time, id),
    ADD KEY idx_hik_event_project_match_time (pro_code, match_status, event_time, id);
