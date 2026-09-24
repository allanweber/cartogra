DROP INDEX IF EXISTS dependencies_metadata_idx;

ALTER TABLE dependencies
    ALTER COLUMN metadata TYPE TEXT USING metadata::text;
