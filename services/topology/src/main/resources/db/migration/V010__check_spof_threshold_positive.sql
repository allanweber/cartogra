-- A threshold below 1 would flag every service with any dependent (or none) as a SPOF.
ALTER TABLE tenant_topology_settings
    ADD CONSTRAINT spof_fan_in_threshold_positive CHECK (spof_fan_in_threshold >= 1);
