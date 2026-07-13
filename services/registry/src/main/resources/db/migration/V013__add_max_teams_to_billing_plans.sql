ALTER TABLE billing_plans ADD COLUMN max_teams INT NOT NULL DEFAULT -1;

UPDATE billing_plans SET max_teams = 2  WHERE slug = 'free';
UPDATE billing_plans SET max_teams = 25 WHERE slug = 'business';
UPDATE billing_plans SET max_teams = -1 WHERE slug = 'enterprise';
