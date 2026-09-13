ALTER TABLE domain_outbox
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at TIMESTAMP(3) NULL AFTER attempt_count,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER next_attempt_at;

ALTER TABLE labor_person
    DROP INDEX uk_person_idcard_hash,
    ADD UNIQUE KEY uk_person_project_idcard (pro_code, idcard_hash);

CREATE TABLE hik_organization_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pro_code VARCHAR(100) NOT NULL,
    org_index_code VARCHAR(100) NOT NULL,
    org_name VARCHAR(200) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hik_org_project (pro_code, org_index_code),
    KEY idx_hik_org_enabled (enabled, pro_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hik_collection_cursor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    mapping_id BIGINT NOT NULL,
    last_event_time TIMESTAMP(3) NULL,
    last_event_id VARCHAR(150) NULL,
    overlap_seconds INT NOT NULL DEFAULT 120,
    last_success_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hik_cursor_mapping (mapping_id),
    CONSTRAINT fk_hik_cursor_mapping FOREIGN KEY (mapping_id) REFERENCES hik_organization_mapping (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hik_attendance_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(150) NOT NULL,
    pro_code VARCHAR(100) NOT NULL,
    org_index_code VARCHAR(100) NULL,
    hik_person_id VARCHAR(100) NULL,
    person_name VARCHAR(100) NULL,
    idcard_type VARCHAR(64) NULL,
    idcard_encrypted TEXT NULL,
    idcard_hash VARCHAR(64) NULL,
    event_time TIMESTAMP(3) NOT NULL,
    direction VARCHAR(64) NOT NULL,
    check_type VARCHAR(64) NOT NULL,
    check_way VARCHAR(64) NOT NULL,
    check_location VARCHAR(300) NULL,
    longitude_value VARCHAR(64) NULL,
    latitude_value VARCHAR(64) NULL,
    door_index_code VARCHAR(100) NULL,
    device_index_code VARCHAR(100) NULL,
    raw_payload_encrypted LONGTEXT NOT NULL,
    match_status VARCHAR(32) NOT NULL DEFAULT 'UNMATCHED',
    match_method VARCHAR(32) NULL,
    matched_person_id BIGINT NULL,
    match_reason VARCHAR(500) NULL,
    received_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hik_event_project (event_id, pro_code),
    KEY idx_hik_event_match (match_status, event_time),
    KEY idx_hik_event_person (pro_code, hik_person_id),
    KEY idx_hik_event_certificate (pro_code, idcard_hash),
    CONSTRAINT fk_hik_event_person FOREIGN KEY (matched_person_id) REFERENCES labor_person (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE person_match_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    attendance_event_id BIGINT NOT NULL,
    previous_person_id BIGINT NULL,
    matched_person_id BIGINT NULL,
    previous_status VARCHAR(32) NULL,
    match_status VARCHAR(32) NOT NULL,
    match_method VARCHAR(32) NULL,
    action_type VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NULL,
    operated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_match_history_event (attendance_event_id, created_at),
    CONSTRAINT fk_match_history_event FOREIGN KEY (attendance_event_id) REFERENCES hik_attendance_event (id),
    CONSTRAINT fk_match_history_previous_person FOREIGN KEY (previous_person_id) REFERENCES labor_person (id),
    CONSTRAINT fk_match_history_person FOREIGN KEY (matched_person_id) REFERENCES labor_person (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE push_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_type VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    pro_code VARCHAR(100) NOT NULL,
    business_key VARCHAR(300) NOT NULL,
    data_version_no INT NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(200) NOT NULL,
    dependency_key VARCHAR(200) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    priority_no INT NOT NULL DEFAULT 100,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMP(3) NULL,
    last_error_code VARCHAR(100) NULL,
    last_error_message VARCHAR(1000) NULL,
    remote_code VARCHAR(100) NULL,
    remote_message VARCHAR(1000) NULL,
    requested_at TIMESTAMP(3) NULL,
    started_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    manual_reason VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_task_idempotency (idempotency_key),
    KEY idx_push_task_dispatch (status, next_retry_at, priority_no, created_at),
    KEY idx_push_task_project (pro_code, task_type, status),
    KEY idx_push_task_dependency (dependency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE integration_call_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    integration_type VARCHAR(32) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    task_id BIGINT NULL,
    request_path VARCHAR(500) NOT NULL,
    request_summary_json JSON NULL,
    http_status INT NULL,
    response_summary_json JSON NULL,
    success BOOLEAN NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(100) NULL,
    error_message VARCHAR(1000) NULL,
    trace_id VARCHAR(100) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_call_log_created (created_at),
    KEY idx_call_log_task (task_id),
    KEY idx_call_log_integration (integration_type, operation_type, success),
    CONSTRAINT fk_call_log_task FOREIGN KEY (task_id) REFERENCES push_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
