# Блок D1 — фиксы layout: логотип и подсветка меню

Ветка: `feat/design-refresh` (от `feat/channels-mvp`)
Тема блока: только навигация и логотип. Схема БД, Security, каналы — не затронуты.

## Что было сломано

1. **Логотип не отображался нигде.** Пять шаблонов ссылались на
   `/static/images/logo-icon.svg`: файла нет ни в одной ветке, а Spring Boot
   раздаёт `static/` от корня (правильно было бы `/images/...`). Кроме того,
   `/images/**` нет в `permitAll` — на login/register файл не загрузился бы
   и после исправления пути.
2. **Подсветка активного пункта меню** работала у 5 пунктов из 9.
   Контроллеры `pricing`, `expenses`, `chat` передают `activePage`,
   но в `layout.html` у этих ссылок не было `th:classappend`.
   `FeedbackAdminController` не передавал `activePage` вовсе.

## Решение

- Логотип — inline SVG во фрагменте `fragments/logo.html`, подключается
  `th:replace="~{fragments/logo :: mark(24)}"`. HTTP-запроса нет, правка
  `SecurityConfig` не нужна. Цвета — классы `.logo-mark__bg/__fg` на токенах
  `--accent` / `--bg-root`, поэтому знак подстроится под светлую тему (блок D2).
- Добавлен `th:classappend` для `/pricing`, `/expenses`, `/feedback-admin`, `/chat`.
- `FeedbackAdminController.page()` передаёт `activePage = "feedback"`
  (тот же ключ, что у `PlaceholderController`).

## Файлы

| Файл | Изменение |
|---|---|
| `templates/fragments/logo.html` | **новый** — фрагмент `mark(size)` |
| `static/css/core.css` | +3 правила `.logo-mark*` после `.sidebar__logo span` |
| `templates/fragments/layout.html` | логотип + 4 × `th:classappend` |
| `templates/pages/auth/login.html` | логотип |
| `templates/pages/auth/register.html` | логотип |
| `templates/pages/auth/welcome.html` | логотип (обёрнут в `div.mb-4` вместо inline margin) |
| `templates/pages/legal/view.html` | логотип |
| `controller/FeedbackAdminController.java` | +1 строка `activePage` |

## Применение

```bash
git checkout feat/channels-mvp && git pull
git checkout -b feat/design-refresh
git am D1-layout-fixes.patch
```

## Проверка на staging

- [ ] Логотип виден: sidebar, `/login`, `/register`, `/legal/...`, страница welcome после регистрации
- [ ] На `/login` в DevTools → Network нет 404/302 на картинки
- [ ] Подсветка в меню: Ценообразование, Расходы, Отзывы, AI Чат
- [ ] Остальные пункты подсвечиваются как раньше

## Замечено попутно (не в этом блоке)

- `PlaceholderController` мапит `GET /feedback` (заглушка «Отзывы гостей»),
  а `/feedback/**` в `permitAll` для гостевой формы. Заглушка, видимо, legacy —
  стоит проверить, нужна ли.
- `/import` и интеграции помечают активным пункт «Настройки», хотя у «Импорт»
  есть свой пункт меню с ключом `import`. Оставлено как есть — возможно, намеренно.
