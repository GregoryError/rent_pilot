-- V11: encrypted values in system_settings
-- Некоторые значения (RC-пароль, API-ключи третьих лиц) хранятся зашифрованными.
-- Признак — is_encrypted=TRUE. При чтении EncryptionUtil расшифровывает.

ALTER TABLE system_settings
    ADD COLUMN IF NOT EXISTS is_encrypted BOOLEAN NOT NULL DEFAULT FALSE;

-- Флаг для существующих чувствительных полей (если уже были добавлены plain-text — надо переписать)
-- rc_password и anthropic_api_key должны быть зашифрованными
