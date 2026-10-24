-- Публичные адреса удалённых аккаунтов (/admin/users/<id>/delete): страница бронирования /b/<slug>,
-- прежние /book/ и /widget/ по секрету, короткие /q/ и /f/, анкета /feedback/<код>, расписание
-- горничной /housekeeper/<код>, фиды /ical/<секрет>.ics. После удаления они отвечают 410, а не 404:
-- поисковики и площадки быстрее перестают к ним обращаться.
--
-- Хранится только SHA-256 от «вид:код» — ни самих кодов, ни того, чьи они были. Строки бессрочные:
-- карточки с QR уже напечатаны, площадки годами опрашивают старые ссылки.
CREATE TABLE IF NOT EXISTS deleted_public_links (
    code_hash  CHAR(64)    PRIMARY KEY,
    kind       VARCHAR(20) NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
