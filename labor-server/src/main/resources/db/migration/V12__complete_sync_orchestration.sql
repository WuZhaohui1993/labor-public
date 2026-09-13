ALTER TABLE project_sync_setting
    ADD COLUMN sync_started BOOLEAN NOT NULL DEFAULT FALSE AFTER push_enabled;

UPDATE project_sync_setting setting_row
JOIN labor_project project ON project.id = setting_row.project_id
SET setting_row.sync_started = TRUE
WHERE EXISTS (
    SELECT 1
    FROM push_task task
    WHERE task.pro_code = project.pro_code
      AND task.status = 'SUCCESS'
);
