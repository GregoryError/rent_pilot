# Guest feedback consent — интеграционные патчи

## 1. Применить миграцию V15

Файл `V15__guest_feedback_consent.sql` из архива → добавит поле `agreed_to_consent_at` в `feedback_responses` и страницу `/legal/feedback-consent`.

## 2. FeedbackResponse.java — добавить поле

Найди в `src/main/java/ru/rentoptima/entity/FeedbackResponse.java`. Добавь:

```java
    @Column(name = "agreed_to_consent_at")
    private java.time.LocalDateTime agreedToConsentAt;
```

## 3. FeedbackController.java

**a) Добавь поле в FeedbackRequest record (в конце файла):**

Найди:
```java
    public record FeedbackRequest(
            String propertyCode,
            String sessionId,
            String guestName,
            ...
```

Добавь в конце record поле:
```java
            Boolean agreedToConsent
```

Не забудь запятую перед новым полем.

**b) В методе submitFeedback — валидация:**

Найди строку:
```java
        response.setGuestName(PdAnonymizer.toInitial(request.guestName()));
```

Прямо перед ней добавь:
```java
        // If guest provided a name, they must have given consent
        boolean hasName = request.guestName() != null && !request.guestName().trim().isEmpty();
        boolean hasConsent = Boolean.TRUE.equals(request.agreedToConsent());
        if (hasName && !hasConsent) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Для указания имени требуется согласие на обработку персональных данных"));
        }
        if (hasName) {
            response.setAgreedToConsentAt(LocalDateTime.now());
        }
```

## 4. feedback/index.html — чекбокс + JS

**a) Найди Step 5:**

```html
    <div class="step" id="step5">
        <div class="step-title">Как вас зовут?</div>
        <input type="text" id="guestName" placeholder="Необязательно">
        <button class="btn" onclick="finish()">Отправить отзыв</button>
        <button class="btn btn-skip" onclick="finish()">Отправить анонимно</button>
    </div>
```

**Замени на:**

```html
    <div class="step" id="step5">
        <div class="step-title">Как вас зовут?</div>
        <input type="text" id="guestName" placeholder="Необязательно" oninput="onNameChange()">

        <div id="consent-block" style="display:none; margin: 12px 0; padding: 12px; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; font-size: 0.85rem; line-height: 1.5;">
            <label style="display: flex; align-items: flex-start; gap: 8px; cursor: pointer;">
                <input type="checkbox" id="consentCheck" onchange="onConsentChange()" style="margin-top: 3px; flex-shrink: 0;">
                <span>Даю <a href="/legal/feedback-consent" target="_blank" style="color: var(--accent);">согласие на обработку персональных данных</a> при отправке отзыва с указанием имени</span>
            </label>
        </div>

        <button class="btn" onclick="finish()" id="submitBtn">Отправить отзыв</button>
        <button class="btn btn-skip" onclick="finishAnonymous()">Отправить анонимно</button>
    </div>
```

**b) В `<script>` найди `function finish()`:**

```javascript
        function finish() {
            data.guestName = document.getElementById('guestName').value;
            data.liked = document.getElementById('liked').value;
            data.improve = document.getElementById('improve').value;
            data.completed = true;
            saveProgress();
            document.getElementById('step5').classList.remove('active');
            document.getElementById('stepDone').classList.add('active');
            document.getElementById('progressBar').style.width = '100%';
        }
```

**Замени на:**

```javascript
        function finish() {
            const name = document.getElementById('guestName').value.trim();
            const consent = document.getElementById('consentCheck').checked;

            if (name && !consent) {
                alert('Для указания имени поставьте отметку о согласии');
                return;
            }

            data.guestName = name || null;
            data.liked = document.getElementById('liked').value;
            data.improve = document.getElementById('improve').value;
            data.agreedToConsent = consent;
            data.completed = true;
            saveProgress();
            done();
        }

        function finishAnonymous() {
            document.getElementById('guestName').value = '';
            document.getElementById('consentCheck').checked = false;
            data.guestName = null;
            data.liked = document.getElementById('liked').value;
            data.improve = document.getElementById('improve').value;
            data.agreedToConsent = false;
            data.completed = true;
            saveProgress();
            done();
        }

        function done() {
            document.getElementById('step5').classList.remove('active');
            document.getElementById('stepDone').classList.add('active');
            document.getElementById('progressBar').style.width = '100%';
        }

        function onNameChange() {
            const name = document.getElementById('guestName').value.trim();
            const block = document.getElementById('consent-block');
            block.style.display = name ? 'block' : 'none';
            updateSubmitBtn();
        }

        function onConsentChange() {
            updateSubmitBtn();
        }

        function updateSubmitBtn() {
            const name = document.getElementById('guestName').value.trim();
            const consent = document.getElementById('consentCheck').checked;
            const btn = document.getElementById('submitBtn');
            btn.disabled = name && !consent;
            btn.style.opacity = btn.disabled ? '0.5' : '1';
        }
```

**c) В `saveProgress()` добавь поле:**

Найди тело fetch с `body: JSON.stringify({...})`. Добавь в объект:

```javascript
                    agreedToConsent: document.getElementById('consentCheck')?.checked || false,
```

## 5. Проверка после деплоя

1. Открой форму отзыва по своей ссылке
2. Дойди до шага 5 (имя)
3. Без имени — только кнопки «Отправить отзыв» и «Отправить анонимно» работают
4. Введи имя — появляется чекбокс, кнопка «Отправить отзыв» disabled
5. Поставь галочку — кнопка активируется
6. Отправь → проверь БД:
   ```bash
   docker exec c43adb215099 psql -U rentoptima -c "SELECT id, guest_name, agreed_to_consent_at FROM feedback_responses ORDER BY id DESC LIMIT 3;"
   ```
7. Проверь `/legal/feedback-consent` — открывается публично

## Коммит:
```
feat: guest feedback consent — checkbox when name entered + consent page
```
