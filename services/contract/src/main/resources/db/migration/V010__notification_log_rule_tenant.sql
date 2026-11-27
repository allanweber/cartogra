-- Bind a Delivery Attempt to the tenant of its Notification Rule. The reference in V009 checks only the rule id.
ALTER TABLE notification_rules
    ADD CONSTRAINT notification_rules_tenant_id_key UNIQUE (tenant_id, id);

ALTER TABLE notification_log
    ADD CONSTRAINT notification_log_rule_tenant_fkey
    FOREIGN KEY (tenant_id, rule_id) REFERENCES notification_rules (tenant_id, id);
