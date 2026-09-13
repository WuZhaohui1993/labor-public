ALTER TABLE data_import_batch
    ADD COLUMN import_scope VARCHAR(32) NOT NULL DEFAULT 'ALL' AFTER uploaded_by;

CREATE INDEX idx_import_batch_scope_created
    ON data_import_batch (import_scope, created_at);

UPDATE push_task
SET status = 'WAITING_CONFIRM',
    manual_reason = '等待按项目启动同步'
WHERE status = 'PENDING'
  AND task_type IN ('PROJECT', 'COMPANY', 'TEAM', 'PERSON');
