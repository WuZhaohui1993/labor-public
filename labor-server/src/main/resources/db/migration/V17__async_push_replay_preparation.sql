ALTER TABLE push_replay_job
    ADD COLUMN selection_json TEXT NULL AFTER scope_description;
