ALTER TABLE project_sync_setting
    ADD COLUMN attendance_completion_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER last_push_slot_at,
    ADD COLUMN attendance_completeness_rate DECIMAL(5, 2) NOT NULL DEFAULT 95.00
        AFTER attendance_completion_enabled;

CREATE TABLE attendance_completion_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pro_code VARCHAR(100) NOT NULL,
    window_start_at TIMESTAMP(3) NOT NULL,
    window_end_at TIMESTAMP(3) NOT NULL,
    target_rate DECIMAL(5, 2) NOT NULL,
    expected_count INT NOT NULL,
    captured_count INT NOT NULL,
    existing_completed_count INT NOT NULL,
    target_count INT NOT NULL,
    generated_count INT NOT NULL,
    generated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_completion_window (pro_code, window_start_at, window_end_at),
    KEY idx_attendance_completion_created (pro_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE hik_attendance_event
    ADD COLUMN attendance_source VARCHAR(32) NOT NULL DEFAULT 'HIKVISION' AFTER raw_payload_encrypted,
    ADD COLUMN completion_run_id BIGINT NULL AFTER attendance_source,
    ADD KEY idx_hik_event_source_time (pro_code, attendance_source, event_time, id),
    ADD KEY idx_hik_event_completion_run (completion_run_id),
    ADD CONSTRAINT fk_hik_event_completion_run
        FOREIGN KEY (completion_run_id) REFERENCES attendance_completion_run (id);

UPDATE sys_menu child
JOIN sys_menu parent ON parent.id = child.parent_id
SET child.sort_no = child.sort_no + 1
WHERE parent.route_name = 'SyncManagement'
  AND child.sort_no >= 2;

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT parent.id, 'PAGE', '考勤统计', 'AttendanceStatistics', '/sync/attendance-statistics',
       'attendance-statistics/index', 'hik:view', 'ri/calendar-check-line',
       2, TRUE, TRUE, FALSE, TRUE
FROM sys_menu parent
WHERE parent.route_name = 'SyncManagement'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.route_name = 'AttendanceStatistics');
