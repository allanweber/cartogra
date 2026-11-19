CREATE UNIQUE INDEX k8s_clusters_tenant_name_unique
    ON k8s_clusters (tenant_id, lower(name))
    WHERE deleted_at IS NULL;
