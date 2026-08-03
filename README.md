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

1. Откройте [Releases](../../releases) и скачайте `NASManager-v1.0.0.apk`.
2. Разрешите установку приложений из выбранного браузера или файлового менеджера.
3. Установите APK и откройте **NAS Manager**.

## Настройка TrueNAS

1. В интерфейсе TrueNAS SCALE создайте API key для отдельного пользователя с доступом к мониторингу, приложениям, алертам и выключению системы.
2. В NAS Manager откройте **Настройки** и укажите URL сервера, API key, MAC-адрес сетевой карты и broadcast-адрес локальной сети.
3. Нажмите **Проверить подключение**, затем **Сохранить**.
4. Включите Wake-on-LAN в BIOS/UEFI сервера и в настройках сетевого адаптера.

Используйте HTTPS с сертификатом, которому доверяет Android: современные user-linked API keys TrueNAS требуют защищённый транспорт. HTTP оставлен только для совместимости со старыми локальными установками. Magic Packet обычно работает лишь внутри одной broadcast-сети; для доступа через интернет рекомендуется VPN, а не проброс UDP-порта.

## Совместимость API

Для TrueNAS 25.04+ приложение использует JSON-RPC 2.0 через WebSocket `/api/current`. Если указан пользователь, применяется `auth.login_ex` с `API_KEY_PLAIN`; без имени пользователя — совместимый `auth.login_with_api_key`. Для TrueNAS 24.10 и старее предусмотрен REST fallback `/api/v2.0`, включая `chart.release.*`. Набор операций зависит от ролей API key и версии сервера.

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

Для подписанной сборки передайте keystore:

```powershell
.\scripts\build-apk.ps1 -Keystore C:\keys\nasmanager.jks -KeystorePassword '<password>'
```

Тест формирования Magic Packet:

```powershell
.\scripts\run-tests.ps1
```

## Security

API key шифруется AES-GCM ключом из Android Keystore, не попадает в backup и отправляется только указанному пользователем TrueNAS. Используйте HTTPS и доверенную Wi-Fi сеть или VPN.

## English

NAS Manager is a native Android client for TrueNAS SCALE. It provides Wake-on-LAN, API-based status and safe shutdown, configurable pool/resource/app/alert dashboards, app lifecycle and update actions, system notifications, light/dark themes, and English/Russian localization. Configure the TrueNAS URL, API key, server MAC and LAN broadcast address in **Settings**. Android 8.0+ is supported.

## License

[MIT](LICENSE)
