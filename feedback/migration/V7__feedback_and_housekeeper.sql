-- V7: housekeeper access & default feedback questions

-- 1. Housekeeper access to property
ALTER TABLE properties ADD COLUMN IF NOT EXISTS housekeeper_code VARCHAR(50);
ALTER TABLE properties ADD COLUMN IF NOT EXISTS housekeeper_pin_hash VARCHAR(255);

-- Fill housekeeper_code for existing rows with random values
UPDATE properties
SET housekeeper_code = substring(md5(random()::text || id::text || clock_timestamp()::text) for 20)
WHERE housekeeper_code IS NULL;

ALTER TABLE properties ALTER COLUMN housekeeper_code SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_properties_housekeeper_code ON properties(housekeeper_code);

-- 2. Rating configuration for pricing/AI
INSERT INTO system_settings (tenant_id, key, value, description) VALUES
    (1, 'rating_window_days', '90', 'Окно для расчёта среднего рейтинга (дни)'),
    (1, 'rating_relief_multiplier_max', '0.04', 'Максимальный множитель ослабления/усиления цены от рейтинга'),
    (1, 'feedback_prompt_max_chars', '500', 'Максимум символов из последних отзывов в промпте AI'),
    (1, 'feedback_recent_count', '5', 'Сколько последних отзывов передавать в AI')
ON CONFLICT DO NOTHING;

-- 3. Seed default feedback questions per tenant (only where none exist yet)
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT DISTINCT tenant_id FROM properties LOOP
        IF NOT EXISTS (SELECT 1 FROM feedback_questions WHERE tenant_id = t.tenant_id) THEN
            INSERT INTO feedback_questions (tenant_id, property_id, question_text, question_type, sort_order, active) VALUES
                (t.tenant_id, NULL, 'Чистота при заезде', 'SCALE', 1, TRUE),
                (t.tenant_id, NULL, 'Понятность инструкций', 'SCALE', 2, TRUE),
                (t.tenant_id, NULL, 'Общая оценка проживания', 'SCALE', 3, TRUE),
                (t.tenant_id, NULL, 'Что понравилось', 'TEXT', 4, TRUE),
                (t.tenant_id, NULL, 'Что стоит улучшить', 'TEXT', 5, TRUE),
                (t.tenant_id, NULL, 'Дополнительные комментарии', 'TEXT', 6, TRUE);
        END IF;
    END LOOP;
END $$;
