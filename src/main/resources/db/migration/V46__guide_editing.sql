-- V46: редактор инструкций и статей (patches/INTEGRATION_ADMIN.md).

-- Изображения статей: варианты лежат в томе загрузок, uploads/guides/<file_key>-<ширина>.<jpg|webp>,
-- адрес — /media/guides/<file_key>-<ширина>.<формат>. Оригинал и EXIF не хранятся (PhotoProcessor).
CREATE TABLE guide_images (
    id                 BIGSERIAL   PRIMARY KEY,
    file_key           CHAR(32)    NOT NULL UNIQUE,
    width              INTEGER     NOT NULL,
    height             INTEGER     NOT NULL,
    widths             VARCHAR(40) NOT NULL,
    has_webp           BOOLEAN     NOT NULL,
    lqip               TEXT,
    created_by_user_id BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT now()
);

ALTER TABLE guides
    ADD CONSTRAINT fk_guides_cover FOREIGN KEY (cover_image_id) REFERENCES guide_images(id) ON DELETE SET NULL;

-- Рабочая копия: что сейчас в редакторе. Автосохранение пишет только сюда — сайт видит
-- опубликованное (guides), пока администратор не нажмёт «Опубликовать».
CREATE TABLE guide_drafts (
    guide_id           BIGINT       PRIMARY KEY REFERENCES guides(id) ON DELETE CASCADE,
    title              VARCHAR(200) NOT NULL,
    slug               VARCHAR(80)  NOT NULL,
    description        VARCHAR(300) NOT NULL DEFAULT '',
    body_md            TEXT         NOT NULL DEFAULT '',
    cover_image_id     BIGINT       REFERENCES guide_images(id) ON DELETE SET NULL,
    hidden_from_search BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order         INTEGER      NOT NULL DEFAULT 100,
    platform           JSONB,
    updated_by_user_id BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    updated_at         TIMESTAMP    NOT NULL DEFAULT now()
);

-- История: каждое сохранение и каждая публикация — версия (кто, когда, весь текст и поля).
-- «Восстановить» загружает версию в рабочую копию.
CREATE TABLE guide_versions (
    id                 BIGSERIAL    PRIMARY KEY,
    guide_id           BIGINT       NOT NULL REFERENCES guides(id) ON DELETE CASCADE,
    version            INTEGER      NOT NULL,
    kind               VARCHAR(20)  NOT NULL CHECK (kind IN ('SAVE', 'PUBLISH', 'RESTORE')),
    title              VARCHAR(200) NOT NULL,
    slug               VARCHAR(80)  NOT NULL,
    description        VARCHAR(300) NOT NULL DEFAULT '',
    body_md            TEXT         NOT NULL DEFAULT '',
    cover_image_id     BIGINT,
    hidden_from_search BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order         INTEGER      NOT NULL DEFAULT 100,
    platform           JSONB,
    created_by_user_id BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (guide_id, version)
);
