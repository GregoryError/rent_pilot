# RentOptima (rent_pilot)

Система оптимизации посуточной аренды квартир. AI-powered ценообразование, интеграция с RealtyCalendar, аналитика, обратная связь от гостей.

## Стек

Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Thymeleaf · Docker · Anthropic API

## Быстрый старт (локально)

```bash
cp .env.example .env
# отредактировать .env — задать DB_PASSWORD
docker compose up --build
```

Открыть http://localhost:8080 → логин `admin` / пароль `admin`

## Деплой (VPS)

Push в `main` → GitHub Actions деплоит на VPS по SSH.

Добавить в **GitHub → Settings → Secrets**:
- `VPS_HOST` — IP сервера
- `VPS_USER` — пользователь SSH
- `VPS_SSH_KEY` — приватный SSH-ключ
- `DB_PASSWORD` — пароль PostgreSQL

На VPS:
```bash
mkdir -p /opt/rentoptima
cd /opt/rentoptima
git clone https://github.com/GregoryError/rent_pilot.git .
```

## Структура

```
src/main/java/ru/rentoptima/
├── config/          # Spring конфигурация
├── controller/      # MVC контроллеры
├── entity/          # JPA сущности
├── repository/      # Spring Data JPA
├── security/        # Auth, multi-tenant context
├── service/         # Бизнес-логика
└── dto/             # DTO объекты

src/main/resources/
├── db/migration/    # Flyway миграции
├── templates/       # Thymeleaf шаблоны
└── static/          # CSS, JS
```
