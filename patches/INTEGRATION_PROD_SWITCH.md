# INTEGRATION_PROD_SWITCH — staging становится prod на optirent.ru

Установка `/opt/rentoptima-staging` (порт 8081) начинает обслуживать **optirent.ru** с веткой
**main**. Прежний prod (`/opt/rentoptima`, порт 8080) получает ветку **legacy-rc** и переезжает
на **pilot.optirent.ru**.

| Домен | Порт | Установка | Ветка | Что отдаёт |
|---|---|---|---|---|
| optirent.ru | 8081 | /opt/rentoptima-staging | main | весь сайт, без basic auth |
| staging.optirent.ru | 8081 | та же | — | `/ical/`, `/q/`, `/f/`; остальное 301 на optirent.ru |
| pilot.optirent.ru | 8080 | /opt/rentoptima | legacy-rc | прежний prod |
| partner.optirent.ru | 8082 | /opt/rentoptima-partner | как было | без изменений |

Код готов. **Переключение на сервере не проводилось и не проверялось** — порядок ниже.

---

## 1. docker-compose.yml из .env

Роль установки задают переменные `.env`. Без них — ровно как на staging до переезда:

| Переменная | По умолчанию | На prod |
|---|---|---|
| `APP_ENVIRONMENT` | `staging` | `prod` |
| `APP_BASE_URL` | `https://staging.optirent.ru` | `https://optirent.ru` |
| `APP_CONTAINER_NAME` | `rentoptima-staging-app` | можно не менять |
| `DB_CONTAINER_NAME` | `rentoptima-staging-db` | можно не менять |
| `UPLOADS_VOLUME` | `rentoptima-staging_uploads` | **не менять** |
| `PGDATA_VOLUME` | `rentoptima-staging_pgdata-staging` | **не менять** |
| `STAGING_APP_PORT` | `8081` | `8081` |
| `DEPLOY_BRANCH` (для `scripts/deploy.sh`) | `feat/channels-mvp` | `main` |

Имя тома — это данные: новое имя даёт пустую базу и пустой каталог фото. Переименовывать тома
только вместе с переносом содержимого — для переезда это не нужно. Имена контейнеров менять
можно (compose пересоздаст контейнеры, том останется), но для переключения это лишний шаг.

Проверка `docker compose config` (compose v2.29.7, `-p rentoptima-staging`):

- прежний файл и новый на `.env` staging — отличаются одной строкой:
  `APP_BASE_URL: https://staging.optirent.ru` (раньше переменная не передавалась, и на staging
  ссылки брались из адреса запроса — результат тот же);
- новый файл на `.env` staging и на `.env` prod — отличаются только `APP_ENVIRONMENT`,
  `APP_BASE_URL` и именами контейнеров, если их задать. Тома и порт те же.

> **До выкладки этого коммита на staging** проверить `.env` установки:
> `grep -E '^(APP_ENVIRONMENT|APP_BASE_URL|DEPLOY_BRANCH)=' /opt/rentoptima-staging/.env`.
> Раньше compose задавал `APP_ENVIRONMENT=staging` сам и `.env` игнорировал. Если `.env`
> скопирован из `.env.example`, в нём `APP_ENVIRONMENT=prod` — staging откроется поисковикам.
> Такие строки до переезда удалить.

### Выкладка

`scripts/deploy.sh` берёт ветку из `DEPLOY_BRANCH` в `.env` установки, а workflow передаёт
ветку, которую выкатывает. Не совпали — выкладка пропускается (`Skip: this install tracks …`
в `/var/log/optirent-staging-deploy.log`):

- `deploy.yml` (push в main) → `deploy.sh main` в `/opt/rentoptima-staging`. Пока
  `DEPLOY_BRANCH` не `main`, ничего не делает — **мерж в main до переключения безопасен**.
  Прежний workflow делал `git pull` в `/opt/rentoptima` и перезаписывал там `.env`: мерж
  новой ветки в main прогнал бы V22–V40 по базе старого prod.
- `deploy-staging.yml` (push в feat/channels-mvp) → `deploy.sh feat/channels-mvp`. После
  переключения пропускается; workflow можно удалить, когда всё закрепится.
- `/opt/rentoptima` (legacy-rc) автодеплоя больше нет — выкладка руками.
- `workflows/deploy.yml` в корне репозитория — старая копия, GitHub её не запускает.

Скрипт переключает checkout на `DEPLOY_BRANCH` (`git checkout -f -B`), поэтому после смены
ветки в `.env` локальная ветка на сервере будет называться `main`.

---

## 2. APP_BASE_URL — единственный источник адреса

Любой адрес, который отдаётся наружу, строит `SiteSettings`:

- `site.linkBase(request)` — когда есть запрос. На prod это всегда `APP_BASE_URL`, с какого
  бы домена ни пришёл запрос. Вне prod (partner, localhost) — адрес запроса, если хост наш.
- `site.getBaseUrl()` — в планировщиках (Telegram-уведомления).
- `site.canonical(request, path)` — `linkBase` + путь.

Было зашито (адрес брался из запроса `ServletUriComponentsBuilder.fromContextPath`, из базы
или из константы):

| Где | Что за адрес |
|---|---|
| `ChannelController.list` | ссылки фидов «Ссылка для площадки» (`/ical/…`) |
| `OnboardingController` (мастер) | ссылки фидов и адрес страницы бронирования |
| `WidgetAdminController.baseUrl` | прямая ссылка `/b/…`, код вставки `w.js`, iframe, адрес страницы, превью фото |
| `WidgetCardController.baseUrl` | **QR `/q/` и `/f/`** на печатной карточке и в скачиваемом SVG |
| `WidgetPublicController.fill` | `/b/…`: шрифты, картинка превью (og:image), JSON-LD, встроенные настройки виджета |
| `WidgetPublicController.shortLink` | `/q/` — `Location` был относительным, теперь полный |
| `WidgetApiController` | настройки виджета на чужих сайтах (фото, шрифты), `.ics` гостя, ссылка в уведомлении хозяину |
| `StaffController` | ссылки горничной `/housekeeper/…` |
| `PropertyController` | ссылки анкеты отзывов и горничной на странице объектов |
| `FeedbackAdminController` | ссылки и QR анкеты отзывов `/feedback/…` |
| `FeedbackController.notifyHost` | «Все отзывы: …» в Telegram |
| `IntegrationsController` → настройка `public_base_url` | адрес запроса запоминался при сохранении Telegram; по нему `AlertSchedulerService` и `WidgetNotifier` строили ссылки, запасной — константа `https://optirent.ru` в обоих |
| `SiteSettings.canonical` | вне prod — адрес запроса |

`public_base_url` больше не читается и не пишется — у хозяев, подключавших Telegram на
staging, там `https://staging.optirent.ru`; строки в `system_settings` безвредны.

Уже шло через `APP_BASE_URL` на prod: canonical, `sitemap.xml`, IndexNow, og:url, письма
(сброс пароля, подтверждение почты).

Не трогали — это не адреса: домен в UID событий iCal (`optirent-manual-…@optirent.ru` —
по нему узнаётся эхо, **менять нельзя**), проверка «это наша ссылка» в `LinkInspector`
(любой `*.optirent.ru/ical/`), `MAIL_FROM`, тексты-заглушки в шаблонах и пример адреса
«optirent.ru/b/ваше-название» на странице модуля.

---

## 3. /q/, /f/, /ical/ на обоих доменах

С адресом staging.optirent.ru уже разосланы ссылки фидов площадкам (Садовая, Суточно) и
напечатаны карточки. nginx пропускает эти три пути со staging.optirent.ru в то же
приложение:

- `/ical/{secret}.ics` — отдаётся как есть, площадкам ничего менять не нужно. В интерфейсе
  новые ссылки будут с optirent.ru; обе работают.
- `/q/{code}` — 302 сразу на `https://optirent.ru/b/…?promo=…&utm_source=…`, без второго
  редиректа через nginx.
- `/f/{code}` — 302 на `https://optirent.ru/f/{code}` (`SiteSettings.isSecondaryHost`).
  Показать анкету на старом домене нельзя: её стили и `POST /api/feedback/submit` ушли бы
  301-м на optirent.ru, а POST после 301 браузер превращает в GET.

Тесты: `OldDomainShortLinksTest`, `SiteSettingsLinkBaseTest`; в
`WidgetBookingIntegrationTest` ожидаемый `Location` у `/q/` теперь полный.

> **Не проверено, есть ли виджет со staging-адресом на чужих сайтах.** Код вставки
> `<script src="https://staging.optirent.ru/w.js">` после переезда сломается: скрипт загрузится
> через 301, но запросы к `staging.optirent.ru/api/widget/…` получат 301 без CORS-заголовков.
> Перед переключением:
> `grep -h 'staging.optirent.ru' /var/log/nginx/access.log* | grep -E '"(GET|POST|OPTIONS) /api/widget/' | awk '{print $11}' | sort | uniq -c`
> (Referer — чужой сайт). Если такие есть — добавить `/api/widget/` и `/w.js` в пропускаемые
> пути блока staging.optirent.ru (как `/ical/`) или попросить хозяина обновить код.

---

## 4. nginx

Конфиги не в репозитории. Пути к сертификатам — как выдаёт certbot; сверить с текущими.

**TLS 1.3 отключён намеренно.** `ssl_protocols` действует на весь `listen` — если оставить
в `nginx.conf` (контекст `http`) или в подключённом
`/etc/letsencrypt/options-ssl-nginx.conf` значение `TLSv1.2 TLSv1.3`, хендшейк может пойти по
нему. Поэтому:

```bash
grep -rn ssl_protocols /etc/nginx/ /etc/letsencrypt/options-ssl-nginx.conf
```

— везде `TLSv1.2`. Если `include /etc/letsencrypt/options-ssl-nginx.conf;` есть в server-блоках,
либо поправить `ssl_protocols` в нём (certbot при обновлении сертификата этот файл не
перезаписывает, но предупредит), либо убрать include и перенести нужное из него в блоки:
два `ssl_protocols` в одном блоке — ошибка `nginx -t`.

### Общие заголовки прокси

`/etc/nginx/snippets/optirent-proxy.conf`:

```nginx
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host  $host;
proxy_set_header X-Forwarded-Port  $server_port;
proxy_read_timeout 120s;
```

`Host` обязателен как есть: по нему приложение отличает staging.optirent.ru от optirent.ru
(редирект `/f/`).

### optirent.ru → 8081

`/etc/nginx/sites-available/optirent.ru`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name optirent.ru www.optirent.ru;
    return 301 https://optirent.ru$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name optirent.ru;

    ssl_certificate     /etc/letsencrypt/live/optirent.ru/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/optirent.ru/privkey.pem;
    ssl_protocols TLSv1.2;          # TLS 1.3 отключён намеренно

    client_max_body_size 100m;
    # basic auth нет

    error_page 502 503 504 /maintenance.html;
    location = /maintenance.html {
        root /var/www/optirent;
        internal;
        add_header Cache-Control "no-store" always;
        add_header Retry-After "60" always;
        add_header X-Robots-Tag "noindex" always;
    }

    # ВРЕМЕННО: вебхук RealtyCalendar прежнего prod. В новом приложении этого адреса нет.
    # Убрать, когда в кабинете RC адрес вебхука сменят на https://pilot.optirent.ru/api/webhooks/rc.
    location = /api/webhooks/rc {
        proxy_pass http://127.0.0.1:8080;
        include /etc/nginx/snippets/optirent-proxy.conf;
    }

    location / {
        proxy_pass http://127.0.0.1:8081;
        include /etc/nginx/snippets/optirent-proxy.conf;
    }
}
```

`www.optirent.ru` по https — только если в сертификате есть www (см. `INTEGRATION_SEO_MOBILE.md`,
раздел nginx). Сжатие и кэш статики — оттуда же, `proxy_pass` в них заменить на 8081.

### staging.optirent.ru: три пути → 8081, остальное → optirent.ru

`/etc/nginx/sites-available/staging.optirent.ru` (заменяет прежний, basic auth убрать):

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name staging.optirent.ru;
    return 301 https://staging.optirent.ru$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name staging.optirent.ru;

    ssl_certificate     /etc/letsencrypt/live/staging.optirent.ru/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/staging.optirent.ru/privkey.pem;
    ssl_protocols TLSv1.2;          # TLS 1.3 отключён намеренно

    # Ссылки фидов у площадок и напечатанные карточки — как есть
    location /ical/ {
        proxy_pass http://127.0.0.1:8081;
        include /etc/nginx/snippets/optirent-proxy.conf;
    }
    location /q/ {
        proxy_pass http://127.0.0.1:8081;
        include /etc/nginx/snippets/optirent-proxy.conf;
    }
    location /f/ {
        proxy_pass http://127.0.0.1:8081;
        include /etc/nginx/snippets/optirent-proxy.conf;
    }

    location / {
        return 301 https://optirent.ru$request_uri;
    }
}
```

Порт 80 уводит на https того же домена, а не сразу на optirent.ru: тогда и `http://…/ical/`
доходит до фида.

### pilot.optirent.ru → 8080

Нужна A-запись `pilot.optirent.ru` → 94.183.236.144 и сертификат
(`sudo certbot certonly --nginx -d pilot.optirent.ru`).

`/etc/nginx/sites-available/pilot.optirent.ru`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name pilot.optirent.ru;
    return 301 https://pilot.optirent.ru$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name pilot.optirent.ru;

    ssl_certificate     /etc/letsencrypt/live/pilot.optirent.ru/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/pilot.optirent.ru/privkey.pem;
    ssl_protocols TLSv1.2;          # TLS 1.3 отключён намеренно

    # Прочие директивы (client_max_body_size и т. п.) — перенести из нынешнего блока optirent.ru.

    # Прежний prod не умеет закрываться от поиска сам — копия сайта не должна попасть в индекс
    add_header X-Robots-Tag "noindex, nofollow" always;
    location = /robots.txt {
        default_type text/plain;
        return 200 "User-agent: *\nDisallow: /\n";
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
        include /etc/nginx/snippets/optirent-proxy.conf;
    }
}
```

---

## 5. Порядок переключения

### Заранее (без простоя)

1. **`.env` staging** — проверить, как в разделе 1 (нет `APP_ENVIRONMENT=prod`). Затем push
   этого коммита в `feat/channels-mvp`: staging выкатится старым `deploy.sh`, на диске появится
   новый.
2. **Виджет со staging-адресом** на чужих сайтах — проверить по логу (раздел 3).
3. **DNS** `pilot.optirent.ru`, сертификат, сниппет `optirent-proxy.conf`, файлы трёх
   конфигов рядом с текущими (`*.new`), **не включая**.
4. **Ветка legacy-rc** из нынешнего main и checkout на неё старого prod:
   ```bash
   git branch legacy-rc origin/main && git push origin legacy-rc        # локально
   cd /opt/rentoptima && git fetch origin legacy-rc \
     && git checkout -B legacy-rc origin/legacy-rc                       # сервер; код тот же
   ```
5. **Мерж `feat/channels-mvp` → main** и push. Новый `deploy.yml` вызовет `deploy.sh main`,
   который пропустит выкладку (`DEPLOY_BRANCH` ещё не задан). Убедиться в логе:
   `Skip: this install tracks feat/channels-mvp, not main`. Старый prod не тронут.
6. **Бэкапы**:
   ```bash
   docker exec rentoptima-staging-db pg_dump -U rentoptima -Fc rentoptima_staging \
     > /root/backup/staging-$(date +%F).dump
   docker run --rm -v rentoptima-staging_uploads:/u -v /root/backup:/b alpine \
     tar czf /b/uploads-$(date +%F).tgz -C /u .
   # и дамп базы старого prod (его контейнер db в /opt/rentoptima)
   cp /opt/rentoptima-staging/.env /root/backup/staging.env.$(date +%F)
   sudo cp -a /etc/nginx/sites-available /root/backup/nginx-sites-$(date +%F)
   ```

7. **Пароли и секреты** — установка была staging с basic auth, а станет открытым prod.
   Бэкап из шага 6 уже есть.
   - **Пароль базы** (`DB_PASSWORD=staging_temp_password`). `POSTGRES_PASSWORD` действует
     только при создании тома, поэтому пароль меняется сначала в самой базе, потом в `.env`:
     ```bash
     cd /opt/rentoptima-staging
     NEW=$(openssl rand -hex 24)
     docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging \
       -c "ALTER USER rentoptima PASSWORD '$NEW'"
     sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$NEW/" .env
     docker compose up -d
     curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/login   # 200 (через минуту)
     ```
     `psql` внутри контейнера входит по локальному сокету без пароля. Если приложение не
     поднялось (`docker compose logs app | grep -i 'password authentication failed'`) —
     пароли разошлись: повторить `ALTER USER` со значением из `.env`.
   - **Пользователь `admin`.** Его создаёт миграция V1 с паролем **`admin`**.
     `APP_ADMIN_PASSWORD` в `.env` не читают ни код, ни `docker-compose.yml` — строку можно
     удалить, она ничего не защищает. Проверить, не остался ли пароль по умолчанию:
     ```bash
     docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging -tAc \
       "SELECT username, active, password_hash = '\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
          FROM users WHERE role = 'ADMIN'"
     ```
     `t` в последней колонке — пароль всё ещё `admin`. Сменить: войти под `admin` →
     «Сменить пароль» внизу меню (от 10 символов). Без входа — через SQL, как в
     `INTEGRATION_AUTH.md` (хеш из `htpasswd -bnBC 10`), с условием `WHERE username = 'admin'`.
   - **SMTP Unisender** — пароль попал в переписку. Выпустить новый в кабинете Unisender,
     записать в `SMTP_PASSWORD`, `docker compose up -d`, проверить письмом с `/forgot`.
   - **`ENCRYPTION_KEY`** — тоже попал в переписку; он общий для prod, staging и partner.
     Просто заменить нельзя: им зашифрованы настройки с `is_encrypted = true` (токен
     Telegram-бота) и пароли Wi-Fi карточек (`widget_cards.wifi_password_enc`), со старым
     ключом они не расшифруются, а перешифровки в коде нет. Варианты: оставить (без доступа к
     базе ключ бесполезен) или сменить и попросить хозяев заново ввести токен Telegram и пароль
     Wi-Fi. Решить до переезда, пока хозяев мало. Если менять — у partner свой ключ, перенос
     данных между установками с этого момента не работает.
   - **`APP_ICAL_EXPORT_CANCELLED=false`** переедет в prod как есть: удалённые ручные записи не
     уходят в фид со `STATUS:CANCELLED`. Если это решение по пилоту — оставить.

### Переключение (минуты)

8. **`.env` в /opt/rentoptima-staging** — дописать:
   ```
   APP_ENVIRONMENT=prod
   APP_BASE_URL=https://optirent.ru
   DEPLOY_BRANCH=main
   ```
   плюс prod-значения из `INTEGRATION_SEO_MOBILE.md` (`INDEXNOW_KEY`, `METRIKA_ID` — только
   после абзаца в политике, `YANDEX_VERIFICATION` / `GOOGLE_VERIFICATION`).
9. **Выкатить main**: `bash /opt/rentoptima-staging/scripts/deploy.sh main` (или «Run workflow» у
   `Deploy to optirent.ru`). Проверить, что внутри новые переменные:
   `docker exec rentoptima-staging-app env | grep -E 'APP_(ENVIRONMENT|BASE_URL)'`.
   Если SHA совпал и скрипт сказал «No changes» (баг 1) — `docker compose up -d` руками:
   переменные из `.env` применяются пересозданием контейнера.
10. **nginx**: включить три новых конфига, `sudo nginx -t && sudo systemctl reload nginx`.

### Сразу после

11. Проверки:
    ```bash
    curl -sI https://optirent.ru/ | head -1                                   # 200, без 401
    curl -sI https://optirent.ru/ | grep -i x-robots-tag                     # пусто (prod индексируется)
    curl -s  https://optirent.ru/robots.txt | head -3                        # не «Disallow: /»
    curl -s  https://optirent.ru/sitemap.xml | grep -m1 '<loc>'              # https://optirent.ru/…
    curl -sI https://staging.optirent.ru/login | grep -i '^location'         # https://optirent.ru/login
    curl -sI 'https://staging.optirent.ru/ical/<secret>.ics' | head -1       # 200, text/calendar
    curl -sI https://staging.optirent.ru/q/<код> | grep -i '^location'       # https://optirent.ru/b/…
    curl -sI https://staging.optirent.ru/f/<код> | grep -i '^location'       # https://optirent.ru/f/<код>
    curl -sI https://pilot.optirent.ru/ | head -1                            # прежний prod
    openssl s_client -connect optirent.ru:443 -servername optirent.ru -tls1_3 </dev/null 2>&1 | grep -E 'alert|Protocol'   # отказ
    openssl s_client -connect optirent.ru:443 -servername optirent.ru -tls1_2 </dev/null 2>&1 | grep 'Protocol'           # TLSv1.2
    ```
    В кабинете: «Площадки» — ссылки начинаются с `https://optirent.ru/ical/`; карточка — QR
    ведёт на `https://optirent.ru/q/…`; письмо «сбросить пароль» — ссылка на optirent.ru;
    Telegram-алерт — «Шахматка: https://optirent.ru/…».
12. **Загрузка**: фото больше 1 МБ на странице бронирования загружается (`client_max_body_size`).
13. **RealtyCalendar**: в кабинете RC сменить адрес вебхука на
    `https://pilot.optirent.ru/api/webhooks/rc`, затем убрать временный `location` из блока
    optirent.ru.
14. **Поисковики**: пункты «prod» из `INTEGRATION_SEO_MOBILE.md` (Вебмастер, Search Console,
    sitemap).
15. **Пользователи старого prod**: сообщить новый адрес pilot.optirent.ru. Сессии на
    optirent.ru у всех сбросятся — это другое приложение.
16. **CLAUDE.md**: таблица «Три окружения» — после того, как переезд закрепится.

## 6. Откат

Трафик возвращается одним `reload` nginx; база нового prod остаётся как есть.

1. Вернуть прежние конфиги:
   `sudo cp -a /root/backup/nginx-sites-<дата>/. /etc/nginx/sites-available/ && sudo nginx -t && sudo systemctl reload nginx`
   (и удалить ссылку на `pilot.optirent.ru` из `sites-enabled`, если её не было).
   optirent.ru снова идёт на 8080, staging.optirent.ru — на 8081 с basic auth.
2. Удалить из `.env` staging три строки, добавленные в шаге 8, и
   `cd /opt/rentoptima-staging && docker compose up -d`. **Не копировать `.env` из бэкапа
   целиком**: там прежние `DB_PASSWORD` и `SMTP_PASSWORD`, а после шага 7 база ждёт новый
   пароль. Если всё же скопировали — вернуть новые значения из шага 7.
   После этого снова `staging`, noindex, ссылки на staging.optirent.ru. С тремя строками
   уходит и `DEPLOY_BRANCH`, поэтому автодеплой
   `feat/channels-mvp` заработает снова; push в main опять будет пропускаться.
3. `/opt/rentoptima` не трогали (только checkout legacy-rc с тем же кодом), его откатывать не
   нужно.

Что не откатывается: брони, регистрации и заявки, созданные на optirent.ru за время
переключения, остаются в базе бывшего staging. Письма и Telegram-сообщения, ушедшие в это
время, содержат ссылки на optirent.ru — после отката они ведут на прежний prod.

## Проверено / не проверено

- `mvn -o test` — зелёный (интеграционные тесты без docker пропускаются, в т. ч.
  `WidgetBookingIntegrationTest` с новым `Location`).
- `docker compose config` — оба варианта `.env`, сравнение с прежним файлом (раздел 1).
- `scripts/deploy.sh` — `bash -n`; разбор `DEPLOY_BRANCH` из `.env` (кавычки, CRLF, пусто).
- **Не проверено**: nginx-конфиги (`nginx -t` на сервере), сертификат pilot, workflows на
  GitHub, переключение целиком.
