# NAS Manager

Нативное Android-приложение для удалённого управления домашним сервером **TrueNAS SCALE**. APK не содержит сторонних runtime-библиотек и работает на Android 8.0+.

## Возможности

- включение сервера с помощью Wake-on-LAN Magic Packet;
- online/offline статус и безопасное выключение через TrueNAS API;
- проверка доступности сервера каждые 10 секунд, пока приложение открыто, со временем последнего пинга и задержкой;
- полное переподключение и обновление dashboard жестом вниз от верхней границы экрана;
- ежедневное расписание включения и выключения сервера;
- настраиваемый dashboard: пулы, нагрузка CPU, RAM, uptime, приложения и алерты;
- Start / Stop / Deploy приложений;
- отображение и запуск обновлений приложений;
- фильтрация алертов и Android-уведомления о новых событиях;
- светлая, тёмная и системная темы;
- русский, английский и системный язык;
- HTTP-подключение по локальной сети с логином и паролем;
- опциональное HTTPS-подключение с API key (предпочтительно) или паролем;
- шифрование пароля и API-ключа с помощью Android Keystore.

## Установка

> **Переход с v1.0.0:** релизы начиная с v1.1.0 подписаны новым постоянным release-key. Android не сможет обновить v1.0.0 напрямую — удалите старую версию перед установкой. v1.3.0 устанавливается поверх v1.1.0/v1.2.0 штатно.

1. Откройте [Releases](../../releases) и скачайте `NASManager-v1.3.0.apk`.
2. Разрешите установку приложений из выбранного браузера или файлового менеджера.
3. Установите APK и откройте **NAS Manager**.

## Настройка TrueNAS

Основной сценарий v1.3.0 — подключение внутри доверенной локальной сети:

1. Создайте в TrueNAS отдельного служебного пользователя без двухфакторной аутентификации и выдайте ему только необходимые роли. Подробности: [настройка сервера](SERVER_SETUP_RU.md).
2. В NAS Manager откройте **Настройки** и укажите `http://<локальный-IP>`, имя пользователя и его пароль.
3. Укажите MAC-адрес сетевой карты и broadcast-адрес локальной сети.
4. Нажмите **Проверить подключение**, затем **Сохранить**.
5. Включите Wake-on-LAN в BIOS/UEFI сервера и в настройках сетевого адаптера.

HTTP не шифрует имя пользователя, пароль и данные API в сети. Используйте этот режим только в доверенной изолированной LAN; для недоверенной Wi-Fi-сети или удалённого доступа сначала подключайтесь через VPN. Не публикуйте Web UI/API TrueNAS в интернет.

HTTPS остаётся доступен как дополнительный режим: укажите `https://...`, имя пользователя и API key (предпочтительно) либо пароль. TrueNAS автоматически отзывает API key при попытке применить его через HTTP, поэтому приложение никогда не использует API key в HTTP-режиме. Magic Packet обычно работает лишь внутри одной broadcast-сети.

## Пинг и расписание

Приложение проверяет доступность настроенного адреса TrueNAS лёгким TCP-подключением раз в 10 секунд. Проверка работает только в foreground, не передаёт логин, пароль или API key и останавливается сразу после сворачивания приложения. Справа от статуса отображаются время последней проверки в формате `HH:mm:ss` и задержка подключения. Это индикатор сетевой доступности: успешный пинг ещё не гарантирует, что API-аутентификация и права пользователя настроены корректно.

Потяните содержимое вниз, когда экран находится в самом верху, чтобы сбросить текущие данные, создать новое API-соединение и заново загрузить dashboard. Жест доступен на экранах обзора, приложений и уведомлений; форма настроек не перезагружается, чтобы не потерять введённые изменения.

В разделе **Настройки → Расписание питания** можно независимо включить ежедневное включение и выключение. Время использует текущий часовой пояс телефона. Для Android 12 и новее необходимо разрешить NAS Manager доступ **Будильники и напоминания**; без него расписания остаются неактивными. Телефон должен быть включён и находиться в нужной LAN/VPN в момент выполнения: включение отправляет Wake-on-LAN, а выключение создаёт новое API-соединение с сохранёнными учётными данными. После перезагрузки телефона, обновления приложения, ручной смены времени или часового пояса расписание восстанавливается автоматически.

## Совместимость API

Приложение рассчитано на TrueNAS SCALE 25.04+ и использует JSON-RPC 2.0 через WebSocket `/api/current`: `ws://` для HTTP и `wss://` для HTTPS. В HTTP-режиме `auth.login_ex` использует `PASSWORD_PLAIN`; в HTTPS-режиме предпочтителен `API_KEY_PLAIN`, а пароль доступен как резервный способ. Deprecated REST `/api/v2.0` и `chart.release.*` не используются. Набор доступных операций зависит от ролей пользователя.

Полезные официальные ссылки: [TrueNAS API Reference](https://www.truenas.com/docs/scale/25.10/api/), [JSON-RPC protocol](https://api.truenas.com/v25.10/jsonrpc.html), [TrueNAS API client](https://github.com/truenas/api_client).

## Сборка

Обычная сборка через Android Gradle Plugin:

```bash
gradle assembleDebug
```

В Windows можно собрать минимальный APK напрямую установленными Android SDK 34 и JDK 11:

```powershell
.\scripts\build-apk.ps1
```

Для подписанной сборки безопаснее передать keystore и gitignored-файл с паролем:

```powershell
.\scripts\build-apk.ps1 -Keystore .\.secrets\nasmanager-release.p12 `
    -KeystorePasswordFile .\.secrets\nasmanager-release.password.txt
```

Новый ключ можно создать скриптом `.\scripts\create-release-key.ps1`. Секретные файлы сохраняются в `.secrets/`, который исключён из Git.

Тест формирования Magic Packet:

```powershell
.\scripts\run-tests.ps1
```

## Security

Пароль и API key шифруются AES-GCM ключом из Android Keystore и не попадают в backup. Cleartext HTTP разрешён только для выбранного пользователем локального TrueNAS и использует пароль — API key по HTTP не отправляется. Сам HTTP не обеспечивает конфиденциальность трафика. Для HTTPS приложение доверяет системным сертификатам Android и установленным пользователем локальным центрам сертификации; проверка имени сервера остаётся обязательной.

## English

NAS Manager is a native Android client for TrueNAS SCALE. It provides Wake-on-LAN, a foreground-only 10-second TCP reachability check with last-ping time and latency, pull-to-reconnect dashboard refresh, daily wake/shutdown schedules, API-based status and safe shutdown, configurable pool/resource/app/alert dashboards, app lifecycle and update actions, notifications, light/dark themes, and English/Russian localization. Local HTTP is the primary mode and authenticates with a username/password; use it only on a trusted LAN or through VPN. Optional HTTPS prefers a username/API key and supports password fallback. Exact schedules require Alarms &amp; reminders access on Android 12+. Android 8.0+ is supported.

## License

[MIT](LICENSE)
