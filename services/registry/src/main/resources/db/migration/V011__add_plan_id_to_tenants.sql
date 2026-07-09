ALTER TABLE tenants ADD COLUMN plan_id UUID REFERENCES billing_plans(id);

UPDATE tenants t
SET plan_id = COALESCE(
    (SELECT id FROM billing_plans WHERE slug = t.plan),
    (SELECT id FROM billing_plans WHERE slug = 'free')
);

ALTER TABLE tenants ALTER COLUMN plan_id SET NOT NULL;
ALTER TABLE tenants DROP COLUMN plan;
