ALTER TABLE labor_company
    ADD COLUMN person_push_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER company_name,
    ADD KEY idx_company_person_push (pro_code, person_push_enabled, status);
