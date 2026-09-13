ALTER TABLE sys_user
    ADD COLUMN email VARCHAR(254) NULL AFTER display_name,
    ADD COLUMN phone VARCHAR(32) NULL AFTER email,
    ADD COLUMN description VARCHAR(500) NULL AFTER phone,
    ADD COLUMN avatar_data LONGTEXT NULL AFTER description,
    ADD COLUMN security_question VARCHAR(200) NULL AFTER avatar_data,
    ADD COLUMN security_answer_hash VARCHAR(100) NULL AFTER security_question,
    ADD COLUMN preferences_json LONGTEXT NULL AFTER security_answer_hash;
