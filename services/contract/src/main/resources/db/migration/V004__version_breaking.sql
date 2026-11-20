-- The verdict of the breaking-change engine for a version against the previous latest version (5.3).
-- NULL means unchecked: the version was stored before 5.3. Version 1 is compared with an empty spec and stores false.
ALTER TABLE contract_versions ADD COLUMN is_breaking BOOLEAN;
