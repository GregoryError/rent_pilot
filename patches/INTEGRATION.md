# Шаг 1: RC-креды per-tenant + шифрование

## 0. Сгенерируй ENCRYPTION_KEY (32 байта в base64)

**На сервере или локально:**
```bash
openssl rand -base64 32
```

Пример вывода: `xkH2NgPFN5wYQ9uw8VvKzFmL3RtBpXcJ4A8zHqW7T5s=`

Скопируй, добавь в `/opt/rentoptima/.env`:
```
ENCRYPTION_KEY=xkH2NgPFN5wYQ9uw8VvKzFmL3RtBpXcJ4A8zHqW7T5s=
```

**Важно:** этот ключ шифрует RC-пароли и другие чувствительные данные. **Никогда не коммить его в git**, не отдавай никому. Если потеряешь — придётся заново настраивать все интеграции у всех пользователей.

Сделай backup файла `.env` где-то (менеджер паролей, зашифрованный архив).

---

## 1. application.yml — новая секция

Добавь в конец файла (или в раздел `spring:`):

```yaml
encryption:
  key: ${ENCRYPTION_KEY:}
```

## 2. docker-compose.yml — передай переменную в контейнер

Найди блок `app:` → `environment:` и добавь строку:
```yaml
      - ENCRYPTION_KEY=${ENCRYPTION_KEY}
```

## 3. SettingsService.java — метод для чувствительных значений

Найди сервис `SettingsService`. Добавь зависимость:

```java
    private final ru.rentoptima.util.EncryptionUtil encryptionUtil;
    private final ru.rentoptima.repository.SystemSettingRepository systemSettingRepo;
```

(Проверь есть ли уже. Скорее всего есть.)

Добавь методы:

```java
    /** Возвращает расшифрованное значение (для is_encrypted=true). */
    public String getEncryptedValue(Long tenantId, String key) {
        String raw = getValue(tenantId, key);
        if (raw == null || raw.isBlank()) return null;
        return encryptionUtil.decrypt(raw);
    }

    /** Устанавливает зашифрованное значение. */
    @org.springframework.transaction.annotation.Transactional
    public void setEncryptedValue(Long tenantId, String key, String plainValue) {
        String encrypted = plainValue == null || plainValue.isBlank()
                ? "" : encryptionUtil.encrypt(plainValue);

        var existing = systemSettingRepo.findByTenantIdAndKey(tenantId, key);
        var setting = existing.orElseGet(() -> {
            var s = new ru.rentoptima.entity.SystemSetting();
            s.setTenantId(tenantId);
            s.setKey(key);
            return s;
        });
        setting.setValue(encrypted);
        setting.setIsEncrypted(true);
        systemSettingRepo.save(setting);
    }
```

Если в `SystemSetting` нет поля `isEncrypted` — добавь:

```java
    @Column(name = "is_encrypted", nullable = false)
    private Boolean isEncrypted = false;
```

С геттером/сеттером (если Lombok — уже есть).

---

## 4. RealtyCalendarClient.java — читать креды из БД

**Приложи полный файл прежде чем менять — покажу точные строки для замены.** Скинь:

```bash
sed -n '1,60p' src/main/java/ru/rentoptima/service/RealtyCalendarClient.java
```

Основная идея:
- Убрать `@Value("${rc.username}")` и `@Value("${rc.password}")`
- Добавить `SettingsService settings` в поля
- Все методы, где используются `rcUsername`/`rcPassword`, теперь принимают `tenantId` и читают:
  ```java
  String username = settings.getValue(tenantId, "rc_username");
  String password = settings.getEncryptedValue(tenantId, "rc_password");
  ```

Проблема — сейчас `RealtyCalendarClient` вызывается из разных мест. Придётся везде проверить что tenantId прокидывается.

---

## 5. Пока не переключаем — миграционный подход

Чтобы не сломать текущую работу на моём tenantId=1:

1. Сначала применяем миграцию V11 (добавляет колонку `is_encrypted`)
2. Ручной шаг — переносим твои текущие креды из env в БД зашифрованно:
   ```java
   // однократно через seed или SQL
   ```
3. Только потом меняем `RealtyCalendarClient` на чтение из БД

---

## Что делать сейчас

1. Сгенерируй ENCRYPTION_KEY и добавь в `.env`
2. Распакуй архив (миграция + EncryptionUtil)
3. Обнови `application.yml` и `docker-compose.yml`
4. Расширь `SettingsService` (два новых метода)
5. **Пришли мне `RealtyCalendarClient.java`** — по нему сделаю точный патч для чтения кредов из БД
6. Собери и задеплой, но пока не меняй RealtyCalendarClient (только миграция + утилита + сервис + env)
7. Проверь что старая работа не сломана

После этого — шаг с миграцией существующих кредов в БД и переключением клиента.

## Коммит:
```
feat: encryption util + settings support for encrypted values (part 1 of RC-creds refactor)
```
