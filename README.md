# Финальная интеграция отзывов v2

## Файлы в архиве — распакуй в корень проекта, они лягут в правильные пути

```
src/main/java/ru/rentoptima/entity/Property.java                (перезапись)
src/main/java/ru/rentoptima/repository/PropertyRepository.java  (перезапись)
src/main/java/ru/rentoptima/repository/FeedbackResponseRepository.java (перезапись)
src/main/java/ru/rentoptima/controller/FeedbackController.java  (перезапись)
src/main/java/ru/rentoptima/controller/HousekeeperController.java (перезапись)
src/main/java/ru/rentoptima/controller/FeedbackAdminController.java (новый)
src/main/java/ru/rentoptima/config/SecurityConfig.java          (перезапись)
src/main/resources/templates/pages/housekeeper/login.html       (перезапись)
src/main/resources/templates/pages/housekeeper/index.html       (новый)
src/main/resources/templates/pages/feedback-admin/index.html    (новый)
```

## Патчи вручную (2 файла)

- `PATCH_PricingEngine.md` — 2 точечные вставки (поле + вызов множителя)
- `PATCH_AiPricingAdvisor.md` — 3 точечные вставки (поле + вычисление + %s в промпте)

## После деплоя

1. Перейти на `/feedback-admin` — увидишь свою страницу отзывов + блок с housekeeper URL
2. Установить PIN горничной (минимум 4 символа) — без него горничная не сможет войти
3. Скопировать housekeeper URL и передать горничной вместе с PIN
4. Отзывы после сбора появляются здесь автоматически (при `completed=true`)
5. Toggle "Показать горничной" — отдельный флаг для каждого отзыва

## Ссылки

- **Гость**: `/feedback/{feedback_code}` — как раньше
- **Горничная**: `/housekeeper/{housekeeper_code}` → ввод PIN → страница с отзывами
- **Админ**: `/feedback-admin` — управление

## Проверка после деплоя

```bash
# Все ли classes в jar
docker compose exec app sh -c "unzip -l /app/app.jar | grep -E 'FeedbackAdmin|Housekeeper'"

# Housekeeper_code сгенерился для property
docker compose exec db psql -U rentoptima -c "SELECT id, name, housekeeper_code, housekeeper_pin_hash IS NOT NULL as has_pin FROM properties;"
```

## Меню

Добавь в главное меню (layout.html) пункт "Отзывы" ведущий на `/feedback-admin`, если ещё нет.
