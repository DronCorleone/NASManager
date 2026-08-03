# NAS Manager 1.0.0

Первый публичный релиз Android-клиента для домашнего сервера TrueNAS SCALE.

- Wake-on-LAN через Magic Packet и отображение online/offline статуса.
- Безопасное выключение TrueNAS через API с подтверждением.
- Dashboard: пулы, CPU/load average, RAM, uptime, приложения и активные алерты.
- Современный JSON-RPC 2.0 WebSocket API для TrueNAS 25.04/25.10/26 и REST fallback для старых SCALE.
- Start / Stop / Deploy приложений и запуск доступных обновлений.
- Настраиваемые разделы dashboard и минимальная важность алертов.
- Android-уведомления о новых алертах.
- Светлая, тёмная и системная темы.
- Русский, английский и системный язык.
- API-ключ хранится с шифрованием Android Keystore.

Поддерживается Android 8.0 (API 26) и новее. Для работы нужен TrueNAS SCALE API key; для TrueNAS 27+ укажите также имя связанного пользователя. Старые версии SCALE поддерживаются через fallback на `chart.release` endpoints.
