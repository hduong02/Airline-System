-- Run once against airline_booking_db before deploying this service version.
-- Applies to both existing and newly created booking_attempt tables.
ALTER TABLE booking_attempt
    ADD COLUMN checkout_claim VARCHAR(36) NULL,
    ADD COLUMN checkout_claim_expires_at DATETIME(6) NULL;
