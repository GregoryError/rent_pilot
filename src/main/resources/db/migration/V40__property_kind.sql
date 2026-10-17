-- V40: тип жилья у объекта — от него зависят слова в интерфейсе («ваша квартира», «цена дома»,
-- «типы номеров» у мини-отеля). Словарь форм — ru.rentoptima.housing.HousingKind.
-- Существующие объекты — квартиры: так их и называл интерфейс до сих пор.

ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'APARTMENT';

ALTER TABLE properties
    ADD CONSTRAINT properties_kind_check
        CHECK (kind IN ('APARTMENT', 'APARTMENTS', 'HOUSE', 'ROOMS', 'OTHER'));
