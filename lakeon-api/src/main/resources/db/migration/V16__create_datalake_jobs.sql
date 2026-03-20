CREATE TABLE datalake_jobs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     VARCHAR(64) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  type          VARCHAR(16) NOT NULL,
  status        VARCHAR(16) NOT NULL,
  spec          JSONB NOT NULL,
  cci_namespace VARCHAR(64),
  ray_job_name  VARCHAR(128),
  k8s_job_name  VARCHAR(128),
  base_image    VARCHAR(256),
  log_obs_path  VARCHAR(512),
  started_at    TIMESTAMPTZ,
  finished_at   TIMESTAMPTZ,
  core_hours    DECIMAL(10,4),
  gpu_hours     DECIMAL(10,4),
  error_message TEXT,
  created_at    TIMESTAMPTZ DEFAULT NOW(),
  updated_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_datalake_jobs_tenant_id ON datalake_jobs(tenant_id);
CREATE INDEX idx_datalake_jobs_status    ON datalake_jobs(status);
