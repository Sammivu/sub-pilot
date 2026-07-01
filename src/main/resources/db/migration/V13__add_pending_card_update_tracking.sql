-- Card-update (self-cure) checkouts can't be found by TSQ reconciliation
-- via "nomba_card_token_ref IS NULL", because the subscription already has
-- an old card token — only a NEW checkout is pending. This column records
-- when a card-update checkout was initiated so the reconciliation job can
-- find ones that never got a webhook confirmation. Cleared the moment the
-- update resolves (via webhook or TSQ) either way.
ALTER TABLE subscriptions ADD COLUMN pending_card_update_at TIMESTAMP NULL;