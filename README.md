# KVN — Android-клиент с Xray и Mihomo

Мобильный VPN-клиент в тёмной теме: большая кнопка подключения, карточка
сервера, пинги, подписки. Внутри два ядра — **xray-core** и **mihomo**, —
ядро выбирается в настройках. Смена ядра или сервера на живом подключении
перезапускает только ядро, TUN остаётся тем же.

Возможности:

- **Аккаунт [sub-lab](https://github.com/x-happy-x/sub-lab)** — вход по логину
  и паролю, подписки аккаунта подтягиваются и синхронизируются сами.
- **Мимикрия под Happ и FlClashX** — для Xray подписка запрашивается как Happ,
  для Mihomo — как FlClashX, со своими заголовками устройства.
- **Выборочное проксирование** — все приложения, только выбранные или все,
  кроме выбранных.
- **Пауза в Wi-Fi** — в любой сети Wi-Fi или только в выбранных VPN снимает
  туннель и поднимает его снова за их пределами.
- **Проверка ресурсов**, как в HomeNet: сайт проверяется напрямую через
  оператора и через VPN по этапам DNS → TCP → TLS → HTTP → объём 16–20 КБ.

```
.
├── core/            Go-библиотека libcore (gomobile → libcore.aar)
│   ├── libcore.go       API для Android: Init/Start/Stop/ParseSubscription/TcpPing…
│   ├── node.go          разбор подписок: ссылки, base64, Clash YAML, Xray JSON
│   ├── convert.go       конвертация сервера между форматами xray ⇄ mihomo
│   ├── engine_xray.go   конфиг и запуск xray-core на fd из VpnService
│   ├── engine_mihomo.go конфиг и запуск mihomo на fd из VpnService
│   ├── scan.go          проверка доступности ресурсов (порт анализатора HomeNet)
│   ├── e2e_test.go      сквозной тест обоих ядер через настоящий TUN
│   └── e2e.sh
├── android/         Android-приложение (Kotlin, Jetpack Compose)
├── build-core.sh    сборка libcore.aar
└── .github/workflows/build.yml  CI: тесты, APK, релизы
```

## Как это работает

- `KvnVpnService` открывает TUN (`172.19.0.1/30`, весь IPv4, по желанию IPv6)
  и исключает из туннеля само приложение, поэтому соединения ядер с серверами
  идут напрямую, без петли.
- Дескриптор TUN уходит в `Libcore.start(engine, node, fd, options)`:
  - **Xray** — встроенный TUN-вход xray-core (`protocol: "tun"`), fd передаётся
    через `xray.tun.fd`, как это делает сам xray на Android;
  - **Mihomo** — `tun.file-descriptor`, стек gVisor, DNS fake-ip.
- Каждое ядро получает свою копию fd (`dup`), оригинал остаётся у сервиса.
  У TUN-входа xray-core нет закрытия, и его gVisor продолжает слушать номер fd
  после остановки. Поэтому номер «забивается» `/dev/null` только на запись,
  а не освобождается, иначе старый стек воровал бы пакеты у следующего ядра
  (это ловит e2e-тест).
- Подписка скачивается приложением (заголовки `subscription-userinfo` и
  `profile-title` показываются как трафик, срок и название) и разбирается в Go.
  Каждый сервер хранится сразу в двух видах — outbound xray и прокси mihomo,
  поэтому одна и та же подписка работает на обоих ядрах.
- Серверы, которые умеет только одно ядро (TUIC, AnyTLS, WireGuard,
  Shadowsocks с плагинами — только mihomo), помечены в списке.
- Трафик считается по UID приложения (`TrafficStats`): одинаково для обоих ядер.

### Подписки и мимикрия

Панели (Remnawave, Marzban, sub-lab) выбирают формат ответа по User-Agent,
поэтому подписка для каждого ядра скачивается отдельно, и серверы хранятся
по ядрам. При переключении ядра недостающие списки докачиваются.

| Ядро | Представляемся | User-Agent | Что обычно отдаёт панель |
|---|---|---|---|
| Xray | Happ | `Happ/3.10.0` | список ссылок или Xray JSON |
| Mihomo | FlClashX | `FlClash X/0.3.2 Platform/android` | Clash YAML |

К обоим добавляются заголовки устройства, как у самих клиентов: `x-hwid`
(ANDROID_ID), `x-device-os: Android`, `x-ver-os`, `x-device-model`,
`x-device-locale`, `Accept-Language`. По ним панели считают устройства и
лимиты. User-Agent можно заменить в «Настройки → Сеть». Из ответа берутся
`subscription-userinfo`, `profile-title` и `announce`, в том числе в виде
`base64:…`.

### Аккаунт sub-lab

«Настройки → Аккаунт sub-lab → Войти»: адрес sub-lab, логин и пароль.
Приложение вызывает `POST /api/auth/password`, получает токен сессии (пароль
не сохраняется) и берёт список подписок из `GET /api/favorites`: свои и
выданные вам, без скрытых. Подписки из sub-lab помечены в списке, удаляются
вместе с доступом на сервере или выходом из аккаунта и обновляются вместе
с остальными. Нужна версия sub-lab с этим endpoint'ом.

### Выборочное проксирование и Wi-Fi

«Настройки → Маршрутизация → Приложения через VPN»: режим и список
приложений (`addAllowedApplication` / `addDisallowedApplication`). Изменения
применяются при выходе с экрана.

«Пауза в Wi-Fi»: в доверенной сети сервис остаётся включённым, но туннель
снимается, а в уведомлении и на главном экране видно «На паузе». Для
режима «в выбранных сетях» Android требует доступ к геопозиции, иначе он
скрывает имя сети, а для работы в фоне — доступ «Разрешить всегда».

### Проверка ресурсов

Вкладка «Проверка» — порт анализатора блокировок HomeNet
(`nginx-proxy-manager/backend/manager/analyzer.go`) в `core/scan.go`. Каждая
цель проверяется двумя путями:

- **напрямую через оператора** — приложение исключено из туннеля, поэтому его
  соединения видит ТСПУ, будто VPN нет;
- **через VPN** — прямо через прокси запущенного ядра (`core.Dial` у xray,
  группа PROXY у mihomo), без локальных портов, которые увидели бы другие
  приложения.

Этапы: DNS (77.88.8.8, 1.1.1.1 и эталон — Cloudflare DoH), TCP, TLS с
настоящим SNI (при отказе — с нейтральным SNI, чтобы отличить фильтр по
имени от блокировки IP), HTTP (заглушки провайдера, 451), объём (обрыв на
16–20 КБ, которым ТСПУ режет зарубежные хостинги). Итог: открыт, обходится
через VPN, VPN мешает, не работает. Готовые наборы те же, что в HomeNet:
белые списки, заблокированные, ИИ, зарубежный хостинг, игры.

### Что поддерживается

| | Xray | Mihomo |
|---|---|---|
| VLESS (Reality, Vision, XTLS), TCP / WS / gRPC / XHTTP / HTTPUpgrade | ✓ | ✓ |
| VMess, Trojan, Shadowsocks | ✓ | ✓ |
| Hysteria2 (без obfs) | ✓ | ✓ |
| TUIC, Hysteria v1, AnyTLS, WireGuard, SS-плагины | — | ✓ |

Форматы подписки: список ссылок (`vless://`, `vmess://`, `trojan://`, `ss://`,
`hysteria2://`, `tuic://`…), он же в base64, Clash/mihomo YAML (`proxies:`),
Xray JSON (полный конфиг или массив — как `?type=json` у [sub-lab](https://github.com/x-happy-x/sub-lab)).

Ещё в настройках: DNS, «локальная сеть напрямую», «.ru/.рф/.su напрямую»,
IPv6, User-Agent для каждого ядра, уровень журнала; журнал ядра и итоговый конфиг видны
в разделе «Диагностика». Есть плитка в шторке и импорт по ссылке
`kvn://import?url=<ссылка на подписку>`.

## Сборка

Нужны Go (версия из `core/go.mod` подтянется сама), JDK 17, Android SDK
(platform 35, build-tools 35) и NDK.

```bash
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
./build-core.sh                         # → android/app/libs/libcore.aar
cd android && ./gradlew assembleRelease # → app/build/outputs/apk/release/
```

APK собирается отдельно под `arm64-v8a`, `armeabi-v7a`, `x86_64` и один
универсальный. Без `android/keystore.properties` релиз подписывается
debug-ключом; для своей подписи положите файл `android/keystore.properties`:

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## CI и релизы

`.github/workflows/build.yml`:

| Событие | Что происходит |
|---|---|
| PR, `workflow_dispatch` | Go-тесты, e2e через TUN, сборка APK → артефакт `kvn-apk` |
| push в `main` | то же + пререлиз **`nightly`**, пересоздаётся на каждом коммите |
| тег `vX.Y.Z` | то же + релиз **KVN X.Y.Z** с APK и `SHA256SUMS` (тег с `-`, например `v1.0.0-rc1`, публикуется как пререлиз) |

Выпустить версию:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

`versionName` берётся из тега, `versionCode` — `1000 + номер запуска`.

Подпись: без секретов APK подписываются debug-ключом раннера, который меняется
от сборки к сборке, поэтому новая версия не встанет поверх старой. Для
стабильной подписи добавьте секреты репозитория (Settings → Secrets → Actions):

| Секрет | Значение |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `KEY_ALIAS` | алиас ключа |
| `KEY_PASSWORD` | пароль ключа (если пусто — берётся пароль хранилища) |

Ключ можно создать так:

```bash
keytool -genkeypair -v -keystore release.jks -alias kvn -keyalg RSA -keysize 4096 -validity 10000
```

## Тесты

```bash
cd core
go test -tags with_gvisor ./...   # разбор подписок; конфиги проверяются загрузчиками xray-core и mihomo
sudo ./e2e.sh                     # оба ядра через настоящий TUN (xray → mihomo → xray) и Scan через каждое
```
