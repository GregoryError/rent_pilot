## AiPricingAdvisor.java — точечный патч (3 места)

### 1. В поля класса

```java
    private final FeedbackAnalyticsService feedbackAnalytics;
```

### 2. В методе `buildPrompt(...)` — где формируется prompt

Найди строку где начинается формирование `String prompt = String.format("""` или подобное.

**Прямо перед этим** посчитай:
```java
    String ratingSection = feedbackAnalytics.buildPromptSection(
            property.getTenant().getId(), property.getId());
```

### 3. Вставить в сам prompt

Найди в prompt-шаблоне место где идут блоки контекста (после конкурентов и перед задачей AI). Добавь новую строку:

```
%s
```

И в параметрах `.format(...)` добавь `ratingSection`.

Пример: если у тебя было:

```java
String prompt = String.format("""
    Анализируй...
    %s
    %s
    Задача: ...
    """,
    dataBlock,
    competitorBlock
);
```

Стало:

```java
String prompt = String.format("""
    Анализируй...
    %s
    %s
    %s
    Задача: ...
    """,
    dataBlock,
    competitorBlock,
    ratingSection
);
```

Всё. Если ratingSection пустой (нет данных о рейтинге) — AI просто увидит пустую строку, ничего не сломается.
