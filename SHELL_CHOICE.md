# TV + Mobile: выбор основы — 27 октября 2026

Для следующего этапа CinemaHall выбрана **ARVIO**, https://github.com/ProdigyV21/ARVIO.
Проверенный commit: `814638307236adb8eb572ed343f126de46b61b27`.
Это решение о следующей основе, а не заявление, что текущий APK уже использует ARVIO.

Причины:

- Один Android-проект с категориями TV и мобильного launcher; `DeviceType.kt` определяет TV / PHONE / TABLET и допускает ручное переопределение.
- Kotlin / Compose, то есть текущий каталог, локальный ARM-процесс и Media3 не требуют смены языка или перехода на Flutter.
- Есть настоящие мобильные компоненты `MobileHeroBanner.kt`, `ArvioMobilePlayer.kt`, `MobilePlayerControls.kt`, `MobilePlayerPanels.kt`, а не только уменьшенная TV-вёрстка.
- В `StreamRepository.kt` уже есть `resolveTorrentViaTorrServer` и выбор файла по M3U. Это клиент внешнего/локального сервера; встроенного движка CinemaHall он не заменяет.
- Apache-2.0 в LICENSE; лицензии зависимостей и права на отдельные изображения сохраняются отдельно.
- Проверены опубликованные изображения `screenshots/home_v190.png`, `details_v190.png`, `mobile_home.webp`. Это скриншоты upstream, не нашего APK.

Сравнение:

| Проект | TV + мобильный | Соответствие CinemaHall |
|---|---|---|
| ARVIO | Один Android-проект, адаптивные режимы | Выбран: Compose, Media3, уже есть клиент TorrServer |
| NuvioTV + NuvioMobile | Раздельные TV и Mobile репозитории | Сильная альтернатива; отдельная интеграция двух приложений |
| Plezy | Flutter, Android/TV и другие платформы | Хороший интерфейс, но архитектура рассчитана на Plex/Jellyfin; больше переделок каталога и смена стека |
| CloudStream | Android, поддержка TV | Сильная система провайдеров; визуально менее близок к требуемому кинотеатру (оценка дизайна) |

Источники:
- https://github.com/ProdigyV21/ARVIO
- https://github.com/NuvioMedia/NuvioTV
- https://github.com/NuvioMedia/NuvioMobile (проверенный commit `61658f366f985fc7758f0942a14c1754c69aca68`)
- https://github.com/edde746/plezy (проверенный commit `f6cb32038275064c4521e21a070cbdc94747c73b`)
- https://github.com/recloudstream/cloudstream

Перенос потребует связать новый UI с нашим каталогом и встроенным TorrServer, сохранить автоматический выбор источников, выбор сезона/серии в карточке и панель «Качество и озвучка» в плеере. Профили, облако и другие интеграции upstream не являются обязательными функциями CinemaHall. Полная сборка и проверка ARVIO на устройствах в рамках этого аудита не выполнялись.
