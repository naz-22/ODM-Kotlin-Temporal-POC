-- Publish-once marker for the DeviationResolved Solace event. Set (only by a conditional
-- UPDATE) after the event was published, so Temporal Activity retries do not publish it again.
ALTER TABLE recovery_deviations ADD COLUMN resolved_event_published_at TIMESTAMPTZ;
