# CinemaHall API и админка

Node.js 24 + MariaDB, слушает только 127.0.0.1:8091. Снаружи доступен через Nginx HTTPS по https://cinemahome.duckdns.org.

## Хранение

В БД: внешние ID, названия RU/EN/оригинал, год, описание, рейтинги, статусы, эпизоды и HTTPS-ссылки. Постеры, фоны, видео, трейлеры и торрент-файлы на сервер НЕ скачиваются и НЕ сохраняются. Временные ссылки для просмотра нужно получать непосредственно перед воспроизведением.

## Установка

1. В backend выполнить npm install --omit=dev --no-audit --no-fund.
2. Применить schema.sql к существующей БД повторно: он не удаляет записи и создаёт admin_settings.
3. В защищённый env-файл systemd-сервиса добавить DB_USER, DB_PASSWORD, DB_NAME, KINOAPI_KEY, ADMIN_PASSWORD_SCRYPT и ADMIN_SESSION_SECRET.
4. Перезапустить node server.mjs. Nginx должен направлять только hostname CinemaHall на 127.0.0.1:8091. QuickShift не затрагивать.

ADMIN_PASSWORD_SCRYPT — scrypt с солью, формат 32 hex символа соли + двоеточие + 128 hex символов хеша. Создать интерактивно на сервере:

    read -rsp "Пароль админки: " CINEMA_ADMIN_PASS; echo; export CINEMA_ADMIN_PASS
    node -e 'const {randomBytes,scryptSync}=require("node:crypto");const salt=randomBytes(16);console.log(salt.toString("hex")+":"+scryptSync(process.env.CINEMA_ADMIN_PASS,salt,64).toString("hex"))'
    unset CINEMA_ADMIN_PASS

ADMIN_SESSION_SECRET — результат команды openssl rand -hex 32. Не хранить пароль, API-ключ или секрет в GitHub. Права env-файла — 0600. Без настроенных секретов вход закрыт. KINOAPI_KEY должен быть доступен самому процессу на VPS: GitHub Secrets не передаются ему автоматически.

## Управление

Откройте https://cinemahome.duckdns.org/admin.

- Список и поиск записей.
- Ручное добавление, изменение названий, года, описаний, альтернативных названий и оценок разных площадок.
- Указание отдельных HTTPS URL вертикального постера и горизонтального баннера без загрузки файлов.
- Импорт по ID Кинопоиска или TMDB.
- Первоначальное наполнение, ручное обновление и ежедневное расписание по Москве (включается в интерфейсе).
- Статус и логи последнего импорта.

Синхронизатор не перезаписывает отредактированные вручную поля. Завершённые релизы при ежедневном обходе пропускаются.

## Диагностика и ручные команды

    node sync-catalog.mjs selftest
    node sync-catalog.mjs bootstrap 50
    node sync-catalog.mjs daily 3
    node sync-catalog.mjs one kinopoisk 535341

Синхронизация напрямую читает KinoAPI, не создаёт JSON-файлов, выполняет менее 4 запросов в секунду и имеет защиту MariaDB GET_LOCK от параллельных запусков. Первоначальная глубина регулируется до 200 страниц на тип.

Публичные маршруты: GET /health, /api/v1/home, /api/v1/search?q=..., /api/v1/titles/:id, /api/v1/titles/:id/seasons.

Административные маршруты /api/admin/* требуют авторизации. Сессия — подписанная Secure HttpOnly SameSite=Strict cookie, запись также требует специальный заголовок. Доступ через HTTPS обязателен.

Android TV пока использует встроенное API. Переключение на внешний сервер — отдельный этап после наполнения БД и проверки контракта ответов.
