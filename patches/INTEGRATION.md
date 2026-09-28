# Шаг 3: регистрация — интеграционные патчи

## 1. User.java — добавить поля

Найди в `src/main/java/ru/rentoptima/entity/User.java` после `private String tgChatId;`:

```java
    @Column(name = "tg_chat_id")
    private String tgChatId;
```

Добавь после этого:

```java
    @Column(unique = true)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = true;

    @Column(name = "email_verification_token")
    private String emailVerificationToken;

    @Column(name = "email_verification_expires_at")
    private java.time.LocalDateTime emailVerificationExpiresAt;

    @Column(name = "password_reset_token")
    private String passwordResetToken;

    @Column(name = "password_reset_expires_at")
    private java.time.LocalDateTime passwordResetExpiresAt;

    @Column(name = "agreed_to_pd_at")
    private java.time.LocalDateTime agreedToPdAt;
```

## 2. UserRepository.java — добавить методы

Найди файл `src/main/java/ru/rentoptima/repository/UserRepository.java`. Добавь методы:

```java
    java.util.Optional<ru.rentoptima.entity.User> findByEmail(String email);

    java.util.Optional<ru.rentoptima.entity.User> findByEmailVerificationToken(String token);

    java.util.Optional<ru.rentoptima.entity.User> findByPasswordResetToken(String token);
```

## 3. SecurityConfig.java — разрешить /register и /welcome

Найди в `SecurityConfig.java` блок `.requestMatchers("/login", ...)` и добавь пути:

Было:
```java
                        .requestMatchers(
                                "/login",
                                "/css/**",
                                ...
                        ).permitAll()
```

Стало:
```java
                        .requestMatchers(
                                "/login",
                                "/register",
                                "/legal/**",
                                "/css/**",
                                ...
                        ).permitAll()
```

## 4. login.html — добавить ссылку на регистрацию

Найди `src/main/resources/templates/pages/auth/login.html`. После формы логина добавь:

```html
<div style="text-align: center; margin-top: 1.25rem; font-size: var(--text-sm); color: var(--text-secondary);">
    Нет аккаунта? <a th:href="@{/register}" style="color: var(--accent);">Создать</a>
</div>
```

Место — обычно перед закрывающим `</div>` login-box.

## 5. Проверка после деплоя

1. Открой `/register` — должна показаться форма
2. Введи любой email, пароль (8+ символов), название компании, чекбокс
3. После сабмита → должно попасть на `/welcome`
4. Проверь БД:

```bash
docker exec c43adb215099 psql -U rentoptima -c "SELECT id, email, username, email_verified, agreed_to_pd_at FROM users ORDER BY id DESC LIMIT 3;"
```

5. Проверь что новый tenant тоже создан:
```bash
docker exec c43adb215099 psql -U rentoptima -c "SELECT id, name, slug FROM tenants ORDER BY id DESC LIMIT 3;"
```

6. Убедись что дефолтные settings созданы для нового tenant:
```bash
docker exec c43adb215099 psql -U rentoptima -c "SELECT COUNT(*) FROM system_settings WHERE tenant_id = (SELECT MAX(id) FROM tenants);"
```

Должно быть ~12.

## Коммит:
```
feat: user registration with tenant creation and welcome page
```

## Что заложено на будущее

- `email_verified` + `email_verification_token` + `expires_at` — для будущего email confirm
- `password_reset_token` + `expires_at` — для восстановления пароля
- `agreed_to_pd_at` — юридический след согласия
- `EmailService` — заглушка, потом заменим SMTP-реализацией без ломки прочих сервисов
- `email` unique в БД

Когда решишь включить email verification:
1. Написать `SmtpEmailService`
2. Поменять `user.setEmailVerified(true)` → `false` в `RegistrationService.register`
3. Добавить контроллер `/verify-email?token=...` для подтверждения
4. Заблокировать логин если `!emailVerified`
5. Аналогично для `/reset-password`
