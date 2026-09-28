# Правовые страницы + админ-раздел

## 1. AuthContext.java — добавить userId()

Проверь есть ли уже:
```bash
grep "userId" src/main/java/ru/rentoptima/security/AuthContext.java
```

Если нет — добавь метод:

```java
    public static Long userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails details) {
            return details.getUserId();
        }
        return null;
    }
```

## 2. SecurityConfig.java — разрешить публичный /legal/**

Уже разрешено в предыдущем патче (`/legal/**` в permitAll). Убедись что есть.

## 3. Включить @PreAuthorize

В SecurityConfig, поверх класса:
```java
@EnableMethodSecurity(prePostEnabled = true)
```

Или на конкретный метод контроллера. Проверь:
```bash
grep "EnableMethodSecurity\|EnableGlobalMethodSecurity" src/main/java/ru/rentoptima/config/SecurityConfig.java
```

Если нет — добавь `@EnableMethodSecurity` на SecurityConfig класс.

## 4. layout.html — раздел "Администрирование"

Найди последний блок с `.nav-section` (у тебя это "Система"). После него — перед `</nav>` — вставь:

```html
            <!-- Admin only -->
            <div sec:authorize="hasRole('ADMIN')">
                <div class="nav-section">Администрирование</div>
                <a th:href="@{/admin/legal-pages}"
                   th:classappend="${activePage == 'admin-legal'} ? 'nav-item--active'"
                   class="nav-item">
                    <span>⚖</span> Правовые страницы
                </a>
            </div>
```

## 5. Сделай себя ADMIN

Твой существующий user id=1 сейчас скорее всего OWNER. Меняем через SQL:

```bash
docker exec c43adb215099 psql -U rentoptima -c "UPDATE users SET role='ADMIN' WHERE id=1;"
```

Разлогинься и залогинься заново — новые authorities подхватятся.

## 6. Проверка после деплоя

1. Открой `/legal/privacy` (без логина) — должна отобразиться страница
2. Открой `/legal/terms` — то же
3. Залогинься как ты (после смены роли на ADMIN)
4. В сайдбаре появится раздел «Администрирование» с пунктом «Правовые страницы»
5. Открой `/admin/legal-pages` — список из 2 страниц
6. Нажми «Редактировать» → измени текст → «Сохранить»
7. Обнови публичную `/legal/privacy` — увидишь изменения

Если новый юзер (OWNER) откроет `/admin/legal-pages` — получит 403 Forbidden.

## Коммит:
```
feat: editable legal pages + admin section (privacy, terms via markdown)
```

## Что заложено

- Таблица `legal_pages` универсальная, можно добавить любую страницу через SQL
- Простой markdown-парсер в LegalController (заголовки, списки, жирный, курсив)
- Роутинг `/legal/{slug}` — динамический, работает для любого slug
- Админ-раздел появится когда добавим другие админские страницы (тарифы, оснащение и т.д.)
- `@PreAuthorize` — стандартный механизм Spring Security, можно вешать на любой контроллер
