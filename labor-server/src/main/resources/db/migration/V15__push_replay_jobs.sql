CREATE TABLE push_replay_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    job_no VARCHAR(64) NOT NULL,
    pro_code VARCHAR(100) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_description VARCHAR(500) NOT NULL,
    task_type VARCHAR(32) NULL,
    company_code VARCHAR(64) NULL,
    company_name VARCHAR(200) NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    total_batch_count INT NOT NULL DEFAULT 0,
    reason VARCHAR(500) NOT NULL,
    requested_by VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    pending_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_replay_job_no (job_no),
    KEY idx_replay_project_created (project_id, created_at),
    CONSTRAINT fk_replay_job_project FOREIGN KEY (project_id) REFERENCES labor_project(id)
);

CREATE TABLE push_replay_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    source_task_id BIGINT NOT NULL,
    replay_task_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    last_error_code VARCHAR(100) NULL,
    last_error_message VARCHAR(1000) NULL,
    completed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_replay_source (job_id, source_task_id),
    UNIQUE KEY uk_replay_task (replay_task_id),
    KEY idx_replay_item_job (job_id, status),
    CONSTRAINT fk_replay_item_job FOREIGN KEY (job_id) REFERENCES push_replay_job(id),
    CONSTRAINT fk_replay_item_task FOREIGN KEY (replay_task_id) REFERENCES push_task(id)
);

ALTER TABLE push_task
    ADD COLUMN company_code VARCHAR(64) NULL,
    ADD COLUMN business_date DATE NULL,
    ADD COLUMN replay_job_id BIGINT NULL,
    ADD COLUMN replay_source_task_id BIGINT NULL,
    ADD KEY idx_push_task_replay_job (replay_job_id),
    ADD KEY idx_push_task_replay_filter (pro_code, replay_job_id, status, task_type, company_code, business_date);

UPDATE push_task
SET business_date = DATE(DATE_ADD(created_at, INTERVAL 8 HOUR))
WHERE business_date IS NULL;

UPDATE push_task task
JOIN labor_company company ON company.id = CAST(task.aggregate_id AS UNSIGNED)
SET task.company_code = company.coll_crop_code
WHERE task.task_type = 'COMPANY';

UPDATE push_task task
JOIN labor_team team ON team.id = CAST(task.aggregate_id AS UNSIGNED)
SET task.company_code = team.coll_crop_code
WHERE task.task_type = 'TEAM';

UPDATE push_task task
JOIN labor_person person ON person.id = CAST(task.aggregate_id AS UNSIGNED)
JOIN labor_team team ON team.pro_code = person.pro_code AND team.team_id = person.team_id
SET task.company_code = team.coll_crop_code
WHERE task.task_type = 'PERSON';

UPDATE push_task task
JOIN hik_attendance_event event_row ON event_row.id = CAST(task.aggregate_id AS UNSIGNED)
LEFT JOIN labor_person person ON person.id = event_row.matched_person_id
LEFT JOIN labor_team team ON team.pro_code = person.pro_code AND team.team_id = person.team_id
SET task.company_code = team.coll_crop_code,
    task.business_date = DATE(DATE_ADD(event_row.event_time, INTERVAL 8 HOUR))
WHERE task.task_type = 'ATTENDANCE';

ALTER TABLE integration_call_log
    ADD COLUMN replay_job_id BIGINT NULL,
    ADD COLUMN batch_size INT NOT NULL DEFAULT 1,
    ADD KEY idx_call_log_replay_job (replay_job_id);
