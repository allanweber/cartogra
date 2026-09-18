-- Supports ProcessedEventReaperScheduler's periodic DELETE ... WHERE processed_at < :threshold.
CREATE INDEX ON processed_events (processed_at);
