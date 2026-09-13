CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    permission_group VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_refresh_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_token_user (user_id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(100) NULL,
    reason VARCHAR(500) NULL,
    result VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    client_ip VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_created_at (created_at),
    KEY idx_audit_actor (actor),
    KEY idx_audit_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE labor_project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pro_code VARCHAR(100) NOT NULL,
    project_name VARCHAR(200) NOT NULL,
    internal_remark VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    data_version_no INT NOT NULL DEFAULT 1,
    source_batch_id BIGINT NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_pro_code (pro_code),
    KEY idx_project_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE labor_company (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pro_code VARCHAR(100) NOT NULL,
    coll_crop_code VARCHAR(64) NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    coll_crop_type VARCHAR(64) NOT NULL,
    china_flag VARCHAR(1) NOT NULL,
    entry_date DATE NULL,
    exit_date DATE NULL,
    contact_name VARCHAR(100) NULL,
    contact_id_type VARCHAR(64) NULL,
    contact_id_encrypted TEXT NULL,
    contact_mobile VARCHAR(32) NULL,
    blacklist_flag VARCHAR(1) NULL,
    internal_remark VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    data_version_no INT NOT NULL DEFAULT 1,
    source_batch_id BIGINT NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_business (pro_code, coll_crop_code),
    KEY idx_company_project (pro_code),
    KEY idx_company_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE labor_team (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id VARCHAR(100) NOT NULL,
    pro_code VARCHAR(100) NOT NULL,
    coll_crop_code VARCHAR(64) NOT NULL,
    team_type VARCHAR(64) NOT NULL,
    team_name VARCHAR(200) NOT NULL,
    entry_date DATE NULL,
    exit_date DATE NULL,
    leader_name VARCHAR(100) NULL,
    leader_id_type VARCHAR(64) NULL,
    leader_id_encrypted TEXT NULL,
    leader_mobile VARCHAR(32) NULL,
    internal_remark VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    data_version_no INT NOT NULL DEFAULT 1,
    source_batch_id BIGINT NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_business (pro_code, team_id),
    KEY idx_team_company (pro_code, coll_crop_code),
    KEY idx_team_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE labor_person (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    idcard_type VARCHAR(64) NOT NULL,
    idcard_encrypted TEXT NOT NULL,
    idcard_hash VARCHAR(64) NOT NULL,
    idcard_start_date DATE NULL,
    idcard_end_date DATE NULL,
    idcard_forever VARCHAR(1) NOT NULL,
    pro_code VARCHAR(100) NOT NULL,
    team_id VARCHAR(100) NOT NULL,
    user_type VARCHAR(64) NOT NULL,
    work_type VARCHAR(64) NOT NULL,
    entry_date DATE NULL,
    exit_date DATE NULL,
    sex VARCHAR(1) NULL,
    birthday DATE NULL,
    mobile VARCHAR(32) NULL,
    team_leader_flag VARCHAR(1) NULL,
    hik_person_id VARCHAR(100) NULL,
    internal_remark VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    data_version_no INT NOT NULL DEFAULT 1,
    source_batch_id BIGINT NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_person_idcard_hash (idcard_hash),
    KEY idx_person_project_team (pro_code, team_id),
    KEY idx_person_hik (pro_code, hik_person_id),
    KEY idx_person_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE data_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_name VARCHAR(255) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    uploaded_by VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    valid_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    new_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    review_comment VARCHAR(1000) NULL,
    reviewed_by VARCHAR(64) NULL,
    reviewed_at TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_import_batch_status (status),
    KEY idx_import_batch_created (created_at),
    KEY idx_import_batch_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE data_import_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    sheet_name VARCHAR(64) NOT NULL,
    excel_row_number INT NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    business_key VARCHAR(300) NULL,
    raw_json JSON NOT NULL,
    normalized_json JSON NULL,
    change_type VARCHAR(32) NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_row_position (batch_id, sheet_name, excel_row_number),
    KEY idx_import_row_batch_type (batch_id, entity_type),
    CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id) REFERENCES data_import_batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE data_import_error (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    import_row_id BIGINT NULL,
    sheet_name VARCHAR(64) NOT NULL,
    excel_row_number INT NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    message VARCHAR(500) NOT NULL,
    raw_value VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_import_error_batch (batch_id, sheet_name, excel_row_number),
    CONSTRAINT fk_import_error_batch FOREIGN KEY (batch_id) REFERENCES data_import_batch (id),
    CONSTRAINT fk_import_error_row FOREIGN KEY (import_row_id) REFERENCES data_import_row (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE master_data_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    business_key VARCHAR(300) NOT NULL,
    version_no INT NOT NULL,
    snapshot_json JSON NOT NULL,
    changed_fields_json JSON NULL,
    source_batch_id BIGINT NULL,
    changed_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_master_version (entity_type, entity_id, version_no),
    KEY idx_master_version_business (entity_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE domain_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE integration_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    integration_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(500) NULL,
    app_key VARCHAR(200) NULL,
    app_secret_encrypted TEXT NULL,
    user_id VARCHAR(200) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config_version INT NOT NULL DEFAULT 1,
    last_test_status VARCHAR(32) NULL,
    last_test_message VARCHAR(500) NULL,
    last_test_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_integration_type (integration_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
