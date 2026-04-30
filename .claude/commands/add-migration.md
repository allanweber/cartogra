You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Create the next Flyway migration file for a service.

Arguments: $ARGUMENTS
(Expected: `<service-name> <short-description>` — e.g., `/add-migration registry add_api_contract_table`)

## Steps

1. **Parse arguments**: service = first word, description = remaining words joined with `_`

2. **Find the migration directory**: `services/<service>/src/main/resources/db/migration/`

3. **Determine the next version number**:
   - List all existing `V*.sql` files in that directory
   - Find the highest version number (e.g., V003)
   - Next version = highest + 1, zero-padded to 3 digits (e.g., V004)
   - If no migrations exist, start at V001

4. **Create the file** named `V<NNN>__<description>.sql` (two underscores before description)

5. **Write the migration skeleton**:
   ```sql
   -- V<NNN>: <description in plain English>
   -- Service: <service-name>

   -- RULES (delete this block before committing):
   --   PKs: UUID DEFAULT gen_random_uuid()
   --   Timestamps: TIMESTAMPTZ (never TIMESTAMP)
   --   tenant_id: UUID NOT NULL on every domain table
   --   Soft deletes: deleted_at TIMESTAMPTZ (never DELETE domain rows)
   --   JSONB metadata: add GIN index if present
   --   Names: snake_case

   -- Write your DDL here:
   ```

6. **If creating a new domain table**, scaffold the full template:
   ```sql
   CREATE TABLE <table_name> (
       id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
       tenant_id    UUID        NOT NULL,
       -- ... domain columns ...
       metadata     JSONB,
       created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
       updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
       deleted_at   TIMESTAMPTZ
   );

   CREATE INDEX ON <table_name> (tenant_id) WHERE deleted_at IS NULL;
   CREATE INDEX ON <table_name> USING GIN (metadata) WHERE metadata IS NOT NULL;

   -- RLS policy
   ALTER TABLE <table_name> ENABLE ROW LEVEL SECURITY;
   CREATE POLICY tenant_isolation ON <table_name>
       USING (tenant_id = current_setting('app.tenant_id')::UUID);
   ```

7. **Verify before finishing:**
   - [ ] Version number does not conflict with existing files
   - [ ] File uses two underscores (`V001__name.sql`)
   - [ ] All timestamps are `TIMESTAMPTZ`
   - [ ] New tables have `tenant_id UUID NOT NULL` and `deleted_at`
   - [ ] PKs use `gen_random_uuid()`
