-- V8: one-shot anonymization of existing personal data
-- Rationale: we choose not to be a PD operator. Full names/phones must not remain.

-- Bookings: full name → initial, phone → NULL
UPDATE bookings
SET
    guest_name = CASE
        WHEN guest_name IS NULL OR guest_name = '' THEN guest_name
        WHEN guest_name LIKE 'Ручное закрытие%' THEN guest_name
        ELSE UPPER(SUBSTRING(TRIM(guest_name) FROM 1 FOR 1)) || '.'
    END,
    guest_phone = NULL;

-- Feedback responses: same
UPDATE feedback_responses
SET
    guest_name = CASE
        WHEN guest_name IS NULL OR guest_name = '' THEN guest_name
        ELSE UPPER(SUBSTRING(TRIM(guest_name) FROM 1 FOR 1)) || '.'
    END,
    guest_phone = NULL;
