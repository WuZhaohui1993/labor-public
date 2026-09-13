CREATE TABLE sys_user_project_role (
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, project_id, role_id),
    KEY idx_user_project_role_project (project_id, user_id),
    CONSTRAINT fk_user_project_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_project_role_project FOREIGN KEY (project_id) REFERENCES labor_project (id),
    CONSTRAINT fk_user_project_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_sync_setting (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    collection_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    hik_collection_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_time TIME NOT NULL DEFAULT '20:00:00',
    zone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    last_push_date DATE NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_sync_setting (project_id),
    CONSTRAINT fk_project_sync_setting_project FOREIGN KEY (project_id) REFERENCES labor_project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO project_sync_setting (project_id)
SELECT id FROM labor_project;

INSERT IGNORE INTO sys_user_project_role (user_id, project_id, role_id)
SELECT ur.user_id, p.id, ur.role_id
FROM sys_user_role ur
CROSS JOIN labor_project p;

ALTER TABLE integration_config
    DROP INDEX uk_integration_type,
    ADD COLUMN project_id BIGINT NULL AFTER id,
    ADD CONSTRAINT fk_integration_config_project FOREIGN KEY (project_id) REFERENCES labor_project (id);

DELETE FROM integration_config
WHERE NOT EXISTS (SELECT 1 FROM labor_project);

UPDATE integration_config config
JOIN (SELECT MIN(id) AS project_id FROM labor_project) first_project
SET config.project_id = first_project.project_id
WHERE config.project_id IS NULL;

INSERT INTO integration_config (
    project_id, integration_type, enabled, config_version, created_at, updated_at, row_version
)
SELECT project.id, integration_type.type_name, FALSE, 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0
FROM labor_project project
CROSS JOIN (
    SELECT 'HIKVISION' AS type_name
    UNION ALL
    SELECT 'LABOR_PLATFORM' AS type_name
) integration_type
LEFT JOIN integration_config existing
       ON existing.project_id = project.id
      AND existing.integration_type = integration_type.type_name
WHERE existing.id IS NULL;

ALTER TABLE integration_config
    MODIFY COLUMN project_id BIGINT NOT NULL,
    ADD UNIQUE KEY uk_project_integration_type (project_id, integration_type),
    ADD KEY idx_integration_type_enabled (integration_type, enabled);

ALTER TABLE data_import_batch
    ADD COLUMN project_id BIGINT NULL AFTER id,
    ADD KEY idx_import_batch_project_created (project_id, created_at),
    ADD CONSTRAINT fk_import_batch_project FOREIGN KEY (project_id) REFERENCES labor_project (id);

UPDATE data_import_batch batch
JOIN (
    SELECT import_row.batch_id, MIN(project.id) AS project_id, COUNT(DISTINCT project.id) AS project_count
    FROM data_import_row import_row
    JOIN labor_project project
      ON project.pro_code = JSON_UNQUOTE(JSON_EXTRACT(import_row.normalized_json, '$.proCode'))
    GROUP BY import_row.batch_id
) resolved ON resolved.batch_id = batch.id AND resolved.project_count = 1
SET batch.project_id = resolved.project_id
WHERE batch.project_id IS NULL;

ALTER TABLE integration_call_log
    ADD COLUMN project_id BIGINT NULL AFTER id,
    ADD KEY idx_call_log_project_created (project_id, created_at),
    ADD CONSTRAINT fk_call_log_project FOREIGN KEY (project_id) REFERENCES labor_project (id);

UPDATE integration_call_log call_log
JOIN push_task task ON task.id = call_log.task_id
JOIN labor_project project ON project.pro_code = task.pro_code
SET call_log.project_id = project.id
WHERE call_log.project_id IS NULL;

ALTER TABLE audit_log
    ADD COLUMN project_id BIGINT NULL AFTER id,
    ADD KEY idx_audit_project_created (project_id, created_at),
    ADD CONSTRAINT fk_audit_project FOREIGN KEY (project_id) REFERENCES labor_project (id);
