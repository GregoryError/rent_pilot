-- V14: consent page + separate consent tracking
INSERT INTO legal_pages (slug, title, content) VALUES
('consent',
 'Согласие на обработку персональных данных',
 '# Согласие на обработку персональных данных

Регистрируясь в сервисе RentOptima, я даю согласие на обработку следующих персональных данных:

- Email
- Название компании
- Данные о моих объектах недвижимости

## Цель обработки

Данные используются исключительно для:
- Авторизации в сервисе
- Работы автопилота ценообразования
- Синхронизации с RealtyCalendar

## Срок обработки

Данные обрабатываются в течение срока использования сервиса. При удалении аккаунта — удаляются в течение 30 дней.

*Полный текст согласия в разработке.*'
);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS agreed_to_terms_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS agreed_to_consent_at TIMESTAMP;