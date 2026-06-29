-- V8: Add 'suspended' as a distinct subscription state
--
-- Previously, "past_due" was overloaded to mean both "actively retrying
-- within the dunning grace period" AND "grace period has fully elapsed,
-- access should now be cut off downstream" — with no way for a webhook
-- consumer to tell the two apart short of independently tracking elapsed
-- days themselves, which defeats the point of SubPilot being the system of
-- record for billing state.
--
-- 'suspended' is now the explicit, webhook-visible signal that grace_period_days
-- has elapsed with no successful recovery. The subscription remains
-- recoverable from 'suspended' (self-cure still works) right up until
-- dunning fully exhausts and it moves to 'cancelled' or 'expired'.
--
-- No existing rows need backfilling: nothing was ever in a state that maps
-- to "suspended" under the old model, since the distinction didn't exist —
-- existing past_due rows correctly remain past_due until the application
-- (running with the new logic going forward) decides whether grace period
-- has elapsed for each of them on its next dunning scheduler pass.

ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscription_status
    CHECK (status IN ('trialing', 'active', 'past_due', 'suspended', 'paused', 'cancelled', 'expired'));

COMMENT ON COLUMN subscriptions.status IS
    'trialing|active|past_due|suspended|paused|cancelled|expired — see SubscriptionStateMachine for valid transitions';