# INTEGRATION_FIRST_RUN — первые впечатления нового пользователя

Ветка `feat/channels-mvp`. По замечаниям внешнего тестировщика, прошедшего кабинет пустого
аккаунта. Каждая фаза — отдельный коммит:

0. Проверка тенанта по всему приложению — **сделано** (раздел 0)
1. Баги: кнопка «Подключить», ложные алерты, /pricing без объекта, падеж месяца
2. Один источник цены — цена квартиры
3. Язык: русские названия метрик, «площадка», «квартира» вместо «категории»
4. Меню, настройки, перенос данных из RealtyCalendar
5. Плашка подтверждения почты — одна строка
6. Итоги, скриншоты

---

## 0. Изоляция тенантов (до фазы 1)

### Правило

Объект, который загружается по id из адреса или формы, должен принадлежать тенанту вошедшего
пользователя — проверкой `tenant_id` после загрузки или условием `tenant_id` в самом запросе.
**Чужой объект — 404, как несуществующий**, а не 403: по ответу нельзя узнать, что такой id у
кого-то есть. Помощник — `security/Tenancy`:

- `Tenancy.own(repo.findById(id), X::getTenantId)` — загрузить и проверить тенант;
- `Tenancy.found(repo.findByIdAndTenantId…(…))` — для запросов с тенантом в условии;
- `Tenancy.notFound()` — для `UPDATE … WHERE id = ? AND tenant_id = ?`, вернувшего 0 строк.

Своё, но в неподходящем состоянии (заявка уже обработана, запись уже удалена, блокировка не с
площадки) — прежнее сообщение на странице, не 404.

### Что было открыто

Найдено при проходе всех контроллеров и сервисов:

| Где | Что можно было сделать с чужим объектом |
|---|---|
| `POST /pricing/competitors/{id}/delete` | **отключить конкурента другого тенанта** (проверки не было вовсе) |
| `POST /import` | **записать брони и расходы на уборку в чужой объект** — бронь получала тенант объекта, т. е. появлялась в чужой шахматке и выручке |
| `POST /expenses` | привязать свой расход к чужому объекту или чужой статье расходов |
| `CompetitorService.deleteSearch` | без проверки; нигде не вызывался — **удалён** |

Остальные места проверяли тенант, но отвечали по-разному: редирект с «не найдено», молчаливый
успех («Объект обновлён»), 500 на несуществующий id, пустой список — теперь везде 404.

### Таблица: эндпоинт → как проверяется

Кабинет (вход обязателен):

| Эндпоинт | Объект из запроса | Проверка |
|---|---|---|
| `POST /settings/properties/{id}/edit`, `/delete` | объект | `UPDATE … WHERE id AND tenant_id`, 0 строк → 404 |
| `POST /settings/properties/{pid}/unit-types` | объект | `Tenancy.own(property)` |
| `POST /settings/properties/{pid}/unit-types/{id}/edit`, `/delete` | категория | `Tenancy.own`, категория обязана принадлежать `{pid}` |
| `POST /settings/channels` | категория `unitTypeId` | `Tenancy.own(unitType)` |
| `POST /settings/channels/{id}/sync`, `/toggle`, `/color`, `/regenerate-secret`, `/delete` | площадка | `ChannelController.ownChannel` → `Tenancy.own` |
| `POST /settings/channels/{id}/import-url` | площадка | `ChannelLinkService.attach`: тенант, активна, iCal; иначе 404 |
| `POST /onboarding/links/{channelId}`, `/later` | площадка | `OnboardingService.ownChannel`: тенант, активна, из мастера |
| `GET /calendar/grid/action/entries` | категория | `Tenancy.own(unitType)`; блоки дополнительно фильтруются по тенанту |
| `POST /calendar/grid/action` | категория | `Tenancy.own(unitType)` |
| `POST /calendar/grid/action/delete` `block:ID` / `booking:ID` | блок / бронь | `Tenancy.own`; своя, но не ручная или уже удалённая — сообщение |
| `POST /calendar/grid/action/toggle-channel-block` | блок | `Tenancy.own`; свой, но не с площадки — сообщение |
| `POST /bookings/pending/{id}/confirm`, `/decline`, `/cancel` | бронь | `PendingBookingController.ownBooking` → `Tenancy.own`; затем сервис ещё раз по тенанту |
| `GET /settings/widgets/{id}`, `/stats`, `/design`, `/frame`; `POST /{id}/update`, `/design`, `/regenerate`, `/delete`, `/photos`, `/promo` | страница бронирования | `ownWidget` → `findByIdAndTenantIdAndActiveTrue` |
| `POST /settings/widgets/create` | категория | `Tenancy.own(unitType)` |
| `POST /settings/widgets/{id}/photos/{photoId}/delete`, `/move` | фото | `findByIdAndWidgetIdAndTenantId` |
| `POST /settings/widgets/{id}/promo/{promoId}/toggle` | промокод | `findByIdAndTenantId` + промокод этой страницы |
| `GET /settings/widgets/{id}/card`, `/card/print`, `/card/qr.svg`; `POST /card`, `/card/promo`, `/card/regenerate`, `/card/wifi-qr` | страница бронирования | `WidgetCardController.widget` → `findByIdAndTenantIdAndActiveTrue` |
| `POST /settings/widgets/{id}/card` `promoCodeId`, `photoId` | промокод, фото | запрос по тенанту / по фото этой страницы; чужой id не сохраняется |
| `POST /expenses` | объект, статья расходов | `Tenancy.own` для обоих |
| `GET /feedback-admin?property=` | объект | среди своих активных; чужой → 404 |
| `POST /feedback-admin/{id}/toggle-housekeeper` | отзыв | отзыв своего объекта, иначе 404 |
| `POST /pricing/competitors/{id}/delete` | конкурент | `CompetitorService.deleteListing(tenantId, id)` |
| `POST /pricing/competitors/{id}/scrape` | конкурент | среди своих активных, иначе 404 |
| `POST /staff/{propertyId}/pin` | объект | среди своих активных, иначе 404 |
| `POST /import`, `POST /import/clear` | объект | `Tenancy.own(property)`; `ImportService` проверяет ещё раз |
| `POST /api/overrides/{id}/cancel` | правило из AI-чата | тенант, иначе 404 (было и раньше) |

Без id из запроса (всё по `AuthContext.tenantId()`): списки страниц, `/settings`,
`/settings/integrations/*`, `/settings/channels/diagnostics/*`, `/dashboard`, `/calendar`,
`/calendar/grid`, `/pricing`, `/expenses`, `/staff`, `/bookings/pending`, `/api/chat`,
`/api/chat/apply-action`, остальные шаги `/onboarding/*` (работают с состоянием своего
пользователя). Настройки пишутся только в существующие ключи своего тенанта.

Публичные (без входа, доступ по секрету или коду, тенант берётся из найденного объекта):
`/b/{slug}`, `/api/widget/{slug}/…`, `/ical/{secret}.ics`, `/q/{code}`, `/f/{code}`,
`/feedback/{code}` и `/api/feedback/submit` (чужой `sessionId` не даёт дописать ответ к другому
объекту), `/housekeeper/{code}` (+ PIN), `/media/widget/**`.

### Тест

`security/TenantIsolationIntegrationTest` (PostgreSQL в контейнере): у пользователей A и B по
полному набору объектов — объект, категория, площадка, блокировка с площадки, ручная запись,
ручная бронь, заявка, страница бронирования, фото, промокод, статья расходов, отзыв,
конкурент, правило AI. A пробует прочитать, изменить и удалить каждый объект B (и подставить
чужой id под свой родительский — своё фото/промокод/категорию под чужой страницей и наоборот):
везде 404, а снимок данных B после всех попыток не изменился. Несуществующий id отвечает так
же, свой объект работает как раньше. На коде до исправления падают все шесть тестов.

### Что учесть

- Устаревшая вкладка с удалённой страницей бронирования или площадкой теперь открывает 404
  вместо редиректа на список с «не найдено».
- Новый эндпоинт с id в адресе или форме — через `Tenancy` и строка в таблице выше.
