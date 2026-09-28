-- Run once against airline_booking_db before deploying the durable-reservation version.
-- NULL state preserves the behavior of existing attempts, whose seats were reserved before commit.
ALTER TABLE booking_attempt
    ADD COLUMN reservation_state VARCHAR(24) NULL,
    ADD COLUMN reservation_seat_ids LONGTEXT NULL,
    ADD COLUMN reservation_claim VARCHAR(36) NULL,
    ADD COLUMN reservation_claim_expires_at DATETIME(6) NULL,
    ADD COLUMN reservation_retry_at DATETIME(6) NULL,
    ADD INDEX idx_booking_attempt_reservation (reservation_state, reservation_retry_at);
