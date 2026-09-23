-- Reconciliation support for ODM autorecovery. Additive; V2 is left untouched because
-- it may already be applied (Flyway checksums).
ALTER TABLE recovery_deviations ADD COLUMN workflow_restarts INTEGER NOT NULL DEFAULT 0;

-- The reconciler looks up unresolved deviations by status + age.
CREATE INDEX idx_recovery_deviations_status_created ON recovery_deviations (status, created_at);
