-- How many reports carried this outcome without the site accepting it. Outcomes the site keeps refusing go to
-- the back of the next report, so they never crowd out the ones it would take.
ALTER TABLE blysisconnect_journal ADD COLUMN report_attempts INTEGER NOT NULL DEFAULT 0;
