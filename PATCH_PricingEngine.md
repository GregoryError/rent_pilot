## PricingEngine.java — точечный патч (2 места)

### 1. Добавить в поля класса (рядом с другими final-полями)

```java
    private final FeedbackAnalyticsService feedbackAnalytics;
```

### 2. В методе `runForProperty`, после `applyCompetitorGravity` и перед `items.add(...)`

Найди эту часть (~строка 220-235):

```java
            BigDecimal finalPrice = applyAiAdjustment(
                    rec, adjustments, tenantId);

            if (!adjustments.containsKey(rec.date())
                    && competitorAnalysis != null
                    && competitorAnalysis.avgPriceByDate().containsKey(rec.date())) {

                finalPrice = applyCompetitorGravity(
                        finalPrice,
                        competitorAnalysis.avgPriceByDate().get(rec.date()),
                        tenantId);
            }

            items.add(
                    new RealtyCalendarClient.SpecialPrice(
                            rec.date(),
                            finalPrice,
                            rec.recommendedMinStay()
                    )
            );
```

Между `if (...applyCompetitorGravity)` и `items.add` вставь:

```java
            // Rating-based multiplier (small nudge based on average guest rating)
            double ratingMult = feedbackAnalytics.priceMultiplier(tenantId, property.getId());
            if (ratingMult != 1.0) {
                finalPrice = finalPrice.multiply(BigDecimal.valueOf(ratingMult))
                                       .setScale(0, java.math.RoundingMode.HALF_UP);
            }
```

Всё, ничего больше в PricingEngine не меняем.
