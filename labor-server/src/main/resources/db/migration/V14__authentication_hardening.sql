ALTER TABLE sys_user
    ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0 AFTER enabled,
    ADD COLUMN failed_login_window_started_at TIMESTAMP(3) NULL AFTER failed_login_attempts,
    ADD COLUMN locked_until TIMESTAMP(3) NULL AFTER failed_login_window_started_at,
    ADD COLUMN last_failed_login_at TIMESTAMP(3) NULL AFTER locked_until,
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 AFTER last_failed_login_at;

CREATE TABLE auth_captcha_challenge (
    captcha_id VARCHAR(64) NOT NULL,
    answer_hmac VARCHAR(64) NOT NULL,
    client_ip VARCHAR(64) NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    consumed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (captcha_id),
    KEY idx_captcha_expires_at (expires_at),
    KEY idx_captcha_consumed_at (consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
