# Настройка автодеплоя feat/channels-mvp → staging

## Что настраиваем

При каждом `git push` в ветку `feat/channels-mvp` GitHub Actions:
1. Подключается к server-khqi по SSH
2. Запускает `/opt/rentoptima-staging/scripts/deploy.sh`
3. Скрипт: `git pull` + `docker compose up --build -d` + проверка что app стартанул

Ты просто пушишь код — через 2-3 минуты новая версия на https://staging.optirent.ru

---

## Шаг 1. Сгенерировать SSH-ключ

**На сервере** (server-khqi):

```bash
# Генерируем пару ключей специально для GitHub Actions
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/gh_deploy_key -N ""
```

Публичный ключ добавим в authorized_keys сервера:

```bash
cat ~/.ssh/gh_deploy_key.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

Приватный ключ — покажем один раз чтобы скопировать в GitHub:

```bash
cat ~/.ssh/gh_deploy_key
```

**Скопируй весь вывод целиком** (начинается с `-----BEGIN OPENSSH PRIVATE KEY-----`, заканчивается `-----END OPENSSH PRIVATE KEY-----`). Он понадобится в Шаге 3.

**После копирования — удаляй файл с сервера НЕ надо**, там он безопасно лежит (только под root). Но публичный ключ в authorized_keys оставь.

---

## Шаг 2. Положить deploy.sh на сервер

Из архива:

```bash
mkdir -p /opt/rentoptima-staging/scripts
cp /tmp/deploy.sh /opt/rentoptima-staging/scripts/deploy.sh  # или как ты положишь
chmod +x /opt/rentoptima-staging/scripts/deploy.sh
```

Проверь:
```bash
ls -la /opt/rentoptima-staging/scripts/deploy.sh
```

Должно быть `-rwxr-xr-x`.

**Важно:** файл будет постоянно перезаписываться при каждом `git pull` (потому что он часть репо в `scripts/`). Это ОК — он и должен там жить.

**Но:** при первом деплое он ещё не будет в /opt/rentoptima-staging из репо. Поэтому сначала мы его кладём вручную (для запуска пайплайна), а после первого пуша он появится как часть репо и будет обновляться автоматически.

---

## Шаг 3. Добавить секреты в GitHub

Открой репозиторий на GitHub → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.

Добавь три секрета:

| Имя | Значение |
|---|---|
| `STAGING_HOST` | `94.183.236.144` |
| `STAGING_USER` | `root` |
| `STAGING_SSH_KEY` | Весь приватный ключ из Шага 1 (с BEGIN/END строками) |

---

## Шаг 4. Положить workflow в репу

Из архива на **локальной** машине:

```bash
git checkout feat/channels-mvp

mkdir -p .github/workflows
cp deploy-staging.yml .github/workflows/deploy-staging.yml

mkdir -p scripts
cp deploy.sh scripts/deploy.sh
chmod +x scripts/deploy.sh

git add .github/workflows/ scripts/
git commit -m "ci: auto-deploy feat/channels-mvp to staging on push"
git push
```

---

## Шаг 5. Проверка

После `git push` открой GitHub → вкладку **Actions**. Должен появиться workflow "Deploy to Staging" с зелёной галочкой.

Если жёлтый (running) — жди 2-3 минуты.
Если красный — открой лог, посмотри где упало.

На сервере посмотри лог:
```bash
tail -f /var/log/optirent-staging-deploy.log
```

## Шаг 6. Проверка автодеплоя

Сделай тестовый push (например поправь README):

```bash
echo "# staging test" >> README.md
git add README.md
git commit -m "test: trigger auto-deploy"
git push
```

Через 2-3 минуты открой https://staging.optirent.ru — должна быть свежая версия.

---

## Troubleshooting

**Permission denied (publickey)** — приватный ключ в secrets поломан. Проверь что скопировал целиком, включая BEGIN/END строки, без лишних пробелов.

**bash: /opt/rentoptima-staging/scripts/deploy.sh: No such file or directory** — скрипт не положен на сервер. Верни в Шаг 2.

**git pull requires authentication** — токен для приватной репы протух. Обнови credentials:
```bash
cd /opt/rentoptima-staging
git remote set-url origin https://GregoryError:НОВЫЙ_ТОКЕН@github.com/GregoryError/rent_pilot.git
git config --global credential.helper store
git pull
# запомнит на будущее
```

**Docker: no space left on device** — старые образы забили диск. Почисти:
```bash
docker system prune -af --volumes=false
```

## После настройки — новый workflow разработки

Локально (Mac):
```bash
git checkout feat/channels-mvp
# работаешь, коммитишь
git commit -am "feat: ..."
git push
```

Автоматически:
- GitHub Actions запускается
- Через 2-3 минуты https://staging.optirent.ru обновлён

Prod (`optirent.ru`) не трогается — там main.
