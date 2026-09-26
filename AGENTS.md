# Repository Guidelines

KVN — Android-клиент с двумя ядрами (xray-core и mihomo) и переключением между ними. Подробности — в `README.md`.

## Структура
- `core/` — Go-библиотека `libcore` (gomobile → `libcore.aar`): разбор подписок (`node.go`), конвертация xray ⇄ mihomo (`convert.go`), запуск ядер на fd TUN (`engine_xray.go`, `engine_mihomo.go`), API для Android (`libcore.go`).
- `android/` — приложение на Kotlin/Jetpack Compose, пакет `io.kvn.client`.
- `build-core.sh` — сборка `android/app/libs/libcore.aar` (нужны `ANDROID_HOME`, `ANDROID_NDK_HOME`).
- `.github/workflows/build.yml` — тесты, APK, пререлиз `nightly` из `main`, релизы по тегам `v*`.

## Команды
- `cd core && go test -tags with_gvisor ./...` — юнит-тесты; конфиги проверяются загрузчиками самих ядер.
- `sudo core/e2e.sh` — оба ядра через настоящий TUN (Linux, root).
- `./build-core.sh && (cd android && ./gradlew assembleRelease)` — APK.

## Стиль
- Go: `gofmt`, тег сборки `with_gvisor` обязателен (gVisor-стек mihomo).
- Kotlin: 4 пробела, официальный code style; комментарии и строки интерфейса — по-русски.
- Коммиты: короткое повелительное описание в sentence case.
