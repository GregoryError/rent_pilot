# Патчи для manual_overrides — часть 2

## 1. PricingEngine.java

**Импорт добавь:**
```java
import ru.rentoptima.service.OverrideResolver;
```

**Поля класса — добавь:**
```java
    private final OverrideResolver overrideResolver;
```

**В методе `runForProperty`** — там где применяется `ratingMult`, добавь **сразу после** блока рейтингового множителя:

Найди:
```java
            // Rating-based multiplier
            if (ratingMult != 1.0) {
                finalPrice = finalPrice
                        .multiply(BigDecimal.valueOf(ratingMult))
                        .setScale(0, RoundingMode.HALF_UP);
            }
```

Добавь после этого блока:
```java
            // Manual override multiplier (from AI chat commands)
            double overrideMult = overrideResolver.getActivePriceMultiplier(
                    tenantId, property.getId(), rec.date());
            if (overrideMult != 1.0) {
                finalPrice = finalPrice
                        .multiply(BigDecimal.valueOf(overrideMult))
                        .setScale(0, RoundingMode.HALF_UP);
                log.debug("Manual override multiplier {} applied for {}", overrideMult, rec.date());
            }
```

---

## 2. DashboardController.java — виджет активных overrides

**Импорты:**
```java
import ru.rentoptima.repository.ManualOverrideRepository;
import java.time.LocalDateTime;
```

**В поля добавь:**
```java
    private final ManualOverrideRepository overrideRepo;
```

**В методе `dashboard` перед `return`:**
```java
        var activeOverrides = overrideRepo.findAllActive(tenantId, LocalDateTime.now());
        model.addAttribute("activeOverrides", activeOverrides);
```

---

## 3. src/main/resources/templates/pages/dashboard/index.html

Добавь после блока «Наблюдения системы» или в любое видное место:

```html
<!-- Active manual overrides -->
<div class="card" th:if="${activeOverrides != null and !#lists.isEmpty(activeOverrides)}"
     style="margin-bottom: var(--sp-5); border-left: 3px solid var(--amber);">
    <div class="card__header">
        <div class="card__title">Активные ручные указания</div>
    </div>
    <div th:each="o : ${activeOverrides}"
         style="padding: var(--sp-3) 0; border-bottom: 1px solid var(--border-subtle); display: flex; justify-content: space-between; align-items: center;">
        <div style="flex:1;">
            <div style="color: var(--text); font-weight: 500;"
                 th:text="${o.description != null and !#strings.isEmpty(o.description) ? o.description : o.overrideType}"></div>
            <div style="color: var(--text-secondary); font-size: var(--text-xs); font-family: var(--font-mono); margin-top: var(--sp-1);"
                 th:text="'Тип: ' + ${o.overrideType} + (${o.expiresAt != null} ? ' · до ' + ${#temporals.format(o.expiresAt, 'dd.MM.yyyy')} : ' · бессрочно')"></div>
        </div>
        <button class="btn btn-sm" style="color: var(--red);"
                th:attr="data-id=${o.id}"
                onclick="cancelOverride(this)">Отменить</button>
    </div>
</div>

<script th:inline="javascript">
async function cancelOverride(btn) {
    if (!confirm('Отменить это указание?')) return;
    const id = btn.getAttribute('data-id');
    btn.disabled = true;
    try {
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
        const headers = { 'Content-Type': 'application/json' };
        if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;

        const resp = await fetch('/api/overrides/' + id + '/cancel', {
            method: 'POST',
            headers
        });
        if (resp.ok) location.reload();
        else alert('Не удалось отменить');
    } catch (e) {
        alert('Ошибка: ' + e.message);
        btn.disabled = false;
    }
}
</script>
```

---

## Коммит:
```
feat: AI chat directives — action extraction, apply endpoint, price_multiplier override in PricingEngine + dashboard widget
```

## Проверка после деплоя:

1. Открой /chat
2. Напиши: «Подними цены на выходные октября на 10%»
3. AI должен выдать текстовый ответ + карточку с подтверждением
4. Нажми «Применить»
5. Проверь запись:

```bash
docker compose exec db psql -U rentoptima -c "SELECT id, override_type, params_json, description, expires_at FROM manual_overrides;"
```

6. На дашборде появится виджет «Активные ручные указания»
7. При следующем цикле автопилота в логах:
   `Manual override multiplier 1.10 applied for 2026-10-XX`
