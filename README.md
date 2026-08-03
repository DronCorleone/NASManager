# NAS Manager

Нативное Android-приложение для удалённого управления домашним сервером **TrueNAS SCALE**. APK не содержит сторонних runtime-библиотек и работает на Android 8.0+.

## Возможности

- включение сервера с помощью Wake-on-LAN Magic Packet;
- online/offline статус и безопасное выключение через TrueNAS API;
- настраиваемый dashboard: пулы, нагрузка CPU, RAM, uptime, приложения и алерты;
- Start / Stop / Deploy приложений;
- отображение и запуск обновлений приложений;
- фильтрация алертов и Android-уведомления о новых событиях;
- светлая, тёмная и системная темы;
- русский, английский и системный язык;
- шифрование API-ключа с помощью Android Keystore.

## Установка

> **Переход с v1.0.0:** v1.1.0 подписана новым постоянным release-key. Android не сможет обновить v1.0.0 напрямую — удалите старую версию и затем установите v1.1.0. Последующие обновления будут совместимы с v1.1.0.

1. Откройте [Releases](../../releases) и скачайте `NASManager-v1.1.0.apk`.
2. Разрешите установку приложений из выбранного браузера или файлового менеджера.
3. Установите APK и откройте **NAS Manager**.

## Настройка TrueNAS

1. Настройте HTTPS-сертификат TrueNAS, которому доверяет Android. Подробности: [настройка сервера](SERVER_SETUP_RU.md).
2. Создайте или сбросьте API key после завершения настройки HTTPS.
3. В NAS Manager откройте **Настройки** и укажите HTTPS URL, имя владельца API key, сам ключ, MAC-адрес сетевой карты и broadcast-адрес локальной сети.
4. Нажмите **Проверить подключение**, затем **Сохранить**.
5. Включите Wake-on-LAN в BIOS/UEFI сервера и в настройках сетевого адаптера.

TrueNAS автоматически отзывает user-linked API key при попытке передать его через HTTP. NAS Manager 1.1.0 поэтому принимает для API только HTTPS и никогда не переключается на REST. Magic Packet обычно работает лишь внутри одной broadcast-сети; для доступа через интернет рекомендуется VPN, а не проброс UDP-порта.

## Совместимость API

Приложение рассчитано на TrueNAS SCALE 25.04+ и использует только JSON-RPC 2.0 через защищённый WebSocket `/api/current`. Аутентификация выполняется через `auth.login_ex` с `API_KEY_PLAIN`; имя пользователя обязательно. Deprecated REST `/api/v2.0` и `chart.release.*` не используются. Набор доступных операций зависит от ролей владельца API key.

Полезные официальные ссылки: [TrueNAS API Reference](https://www.truenas.com/docs/scale/api/), [JSON-RPC protocol](https://api.truenas.com/v26.0/jsonrpc.html), [TrueNAS API client](https://github.com/truenas/api_client).

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

API key шифруется AES-GCM ключом из Android Keystore, не попадает в backup и отправляется только указанному пользователем TrueNAS. Cleartext HTTP запрещён. Приложение доверяет системным сертификатам Android и установленным пользователем локальным центрам сертификации; проверка имени сервера остаётся обязательной.

## English

NAS Manager is a native Android client for TrueNAS SCALE. It provides Wake-on-LAN, API-based status and safe shutdown, configurable pool/resource/app/alert dashboards, app lifecycle and update actions, system notifications, light/dark themes, and English/Russian localization. Configure the TrueNAS URL, API key, server MAC and LAN broadcast address in **Settings**. Android 8.0+ is supported.

## License

[MIT](LICENSE)
