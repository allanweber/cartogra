---
mode: 'agent'
description: 'Create the next numbered Flyway migration file for a service'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Create the next Flyway migration file for a service.

**Usage:** provide `<service-name> <short-description>` (e.g., `registry add_api_contract_table`)

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
   - [ ] Table and column names are `snake_case`
