-- V41: на сколько дней вперёд горничная видит уборки (выезды) на своей странице.
-- Задаётся у объекта в разделе «Сотрудники», рядом со ссылкой горничной и PIN.

ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS housekeeper_horizon_days INTEGER NOT NULL DEFAULT 30;

ALTER TABLE properties
    ADD CONSTRAINT properties_housekeeper_horizon_days_check
        CHECK (housekeeper_horizon_days BETWEEN 7 AND 180);
