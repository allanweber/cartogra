-- Second key for guest mode (ADR-0019 §10): guests can only enter a tenant created as a public sandbox.
ALTER TABLE tenants ADD COLUMN public_sandbox BOOLEAN NOT NULL DEFAULT false;
