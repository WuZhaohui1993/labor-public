ALTER TABLE labor_person
    ADD COLUMN politics_status VARCHAR(64) NULL AFTER exit_date,
    ADD COLUMN edu_level VARCHAR(64) NULL AFTER politics_status,
    ADD COLUMN marital_status VARCHAR(64) NULL AFTER edu_level,
    ADD COLUMN idcard_address VARCHAR(500) NULL AFTER sex,
    ADD COLUMN home_address VARCHAR(500) NULL AFTER idcard_address,
    ADD COLUMN nation VARCHAR(64) NULL AFTER birthday,
    ADD COLUMN country_code VARCHAR(64) NULL AFTER nation,
    ADD COLUMN province_code VARCHAR(64) NULL AFTER country_code,
    ADD COLUMN positive_idcard_image_encrypted LONGTEXT NULL AFTER province_code,
    ADD COLUMN negative_idcard_image_encrypted LONGTEXT NULL AFTER positive_idcard_image_encrypted,
    ADD COLUMN head_image_encrypted LONGTEXT NULL AFTER negative_idcard_image_encrypted;

ALTER TABLE push_task
    ADD COLUMN payload_encrypted LONGTEXT NULL AFTER dependency_key;
