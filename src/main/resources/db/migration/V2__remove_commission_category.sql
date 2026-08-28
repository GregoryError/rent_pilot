-- Комиссии площадок считаются автоматически из bookings.commission
-- Нет смысла вносить их вручную в расходы
DELETE FROM expense_categories WHERE tenant_id = 1 AND name = 'Комиссии площадок';
