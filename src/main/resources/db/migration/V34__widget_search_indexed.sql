-- Страница бронирования /b/<slug> в поиске: решает хозяин. По умолчанию — показывать.
ALTER TABLE booking_widgets
    ADD COLUMN search_indexed BOOLEAN NOT NULL DEFAULT TRUE;
