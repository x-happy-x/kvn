# KVN — Android-клиент с Xray и Mihomo

Мобильный VPN-клиент в тёмной теме: большая кнопка подключения, карточка
сервера, пинги, подписки. Внутри два ядра — **xray-core** и **mihomo** — и
переключатель между ними прямо на главном экране. Смена ядра или сервера
на живом подключении перезапускает только ядро, TUN остаётся тем же.

```
.
├── core/            Go-библиотека libcore (gomobile → libcore.aar)
│   ├── libcore.go       API для Android: Init/Start/Stop/ParseSubscription/TcpPing…
│   ├── node.go          разбор подписок: ссылки, base64, Clash YAML, Xray JSON
│   ├── convert.go       конвертация сервера между форматами xray ⇄ mihomo
│   ├── engine_xray.go   конфиг и запуск xray-core на fd из VpnService
│   ├── engine_mihomo.go конфиг и запуск mihomo на fd из VpnService
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

Настройки: DNS, «локальная сеть напрямую», «.ru/.рф/.su напрямую», IPv6,
User-Agent подписки, уровень журнала; журнал ядра и итоговый конфиг видны
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
sudo ./e2e.sh                     # оба ядра через настоящий TUN, с переключением xray → mihomo → xray
```
