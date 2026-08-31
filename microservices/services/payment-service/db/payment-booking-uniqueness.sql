-- Run against airline_payment_db before deploying the payment initiation change.
-- Inspect duplicates first. Resolve their Stripe sessions and payment history
-- before consolidating records; do not blindly delete payments that may be paid.
SELECT booking_id, COUNT(*) AS payment_count, GROUP_CONCAT(id ORDER BY id) AS payment_ids
FROM payment
GROUP BY booking_id
HAVING COUNT(*) > 1;

-- Fails if duplicates remain. Skip if this named constraint already exists.
ALTER TABLE payment ADD CONSTRAINT uk_payment_booking UNIQUE (booking_id);
