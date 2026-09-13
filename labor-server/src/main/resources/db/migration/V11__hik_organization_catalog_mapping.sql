ALTER TABLE hik_organization_mapping
    ADD COLUMN org_path VARCHAR(1000) NULL AFTER org_name,
    ADD COLUMN mapping_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' AFTER org_path,
    ADD COLUMN include_children BOOLEAN NOT NULL DEFAULT FALSE AFTER mapping_source;
