INSERT INTO system_settings (tenant_id, key, value, description)
VALUES (1, 'autopilot_interval_minutes', '60', 'Интервал автопилота в минутах')
ON CONFLICT (tenant_id, key) DO NOTHING;
