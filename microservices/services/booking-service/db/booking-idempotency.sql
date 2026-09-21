-- Run against airline_booking_db before deploying the booking service.
-- Existing booking/passenger data is unchanged. No backfill is required.
CREATE TABLE IF NOT EXISTS booking_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    booking_id BIGINT NULL,
    payment_request LONGTEXT NULL,
    checkout_response LONGTEXT NULL,
    INDEX idx_booking_attempt_booking (booking_id),
    CONSTRAINT uk_booking_attempt_user_key UNIQUE (user_id, idempotency_key)
);
