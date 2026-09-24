<div align="center">

# Detoxin

**Умный цифровой детокс без слежки · Smart digital detox without tracking**

![Network](https://img.shields.io/badge/network-0%20kb-2ea44f)
![minSdk](https://img.shields.io/badge/minSdk-29-3ddc84?logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)
![Material 3](https://img.shields.io/badge/Material%203-design-757de8)
![Pure Canvas](https://img.shields.io/badge/charts-pure%20Canvas-ff7043)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)

[Русский](#русский) · [English](#english)

</div>

---

## Русский

### Идея

Detoxin обрывает бесконечные ленты, но не отбирает телефон целиком.

- **Блокирует** YouTube Shorts (возвращает назад) и «Клипы» с «Лентой» ВКонтакте (переключает на сообщения).
- **Сохраняет** то, ради чего вы открыли приложение: сообщения ВКонтакте, профиль и обычные длинные видео на YouTube.
- **Ограничивает** TikTok (15 минут в час и 1 час в день) и YouTube (1.5 часа в день) экраном отдыха с обратным отсчётом.
- **Считает время честно** — по системной статистике Android, как «Цифровое благополучие».
- **Помогает** вырабатывать привычки: стрики, темп месяц к месяцу, матрица дней недели и виджет с 30-дневным циклом (например, для контактных линз).

### Приватность

- В приложении **нет разрешения `INTERNET`**, передавать данные ему физически нечем.
- Нет аналитики, рекламы и телеметрии.
- Все данные хранятся в локальной базе SQLite на устройстве и не попадают в облачные резервные копии (`allowBackup=false`).
- Подробно: [политика конфиденциальности](PRIVACY.md).

### Установка

**Готовый APK.** Скачайте файл `detoxin-vX.Y.apk` из раздела [Releases](https://github.com/vladvikhrov/detoxin/releases) и откройте его на телефоне.

После установки:

1. «Настройки → Специальные возможности → Detoxin»: включите службу. Если пункт недоступен на Android 13+: «Приложения → Detoxin → ⋮ → Разрешить ограниченные настройки».
2. «Доступ к истории использования → Detoxin»: включите, чтобы считалось экранное время.
3. «Приложения → Detoxin → Поверх других приложений»: разрешите показ экрана отдыха.
4. Добавьте виджет «Привычка Detoxin» на рабочий стол.

При первом запуске приложение само объясняет каждое разрешение и открывает нужные настройки.

**Сборка из исходников** (нужен JDK 17+ и Android SDK):

```bash
git clone https://github.com/vladvikhrov/detoxin.git
cd detoxin
./scripts/generate-release-keystore.sh   # необязательно: собственный ключ подписи
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Без `keystore.properties` release-сборка подписывается debug-ключом, поэтому проект собирается сразу после клонирования.

### Поддержать автора

Detoxin бесплатный и без рекламы. Если он вам помог:

- Boosty: `https://boosty.to/<логин>`
- CloudTips: `https://pay.cloudtips.ru/p/<id>`
- СБП: `<номер или ссылка>`

---

## English

### The idea

Detoxin cuts infinite feeds without taking your phone away.

- **Blocks** YouTube Shorts (navigates back) and VK "Clips" and "Feed" (switches to Messenger).
- **Keeps** what you actually came for: VK messages, your profile and regular long-form YouTube videos.
- **Limits** TikTok (15 min per hour, 1 h per day) and YouTube (1.5 h per day) with a rest screen and countdown.
- **Measures time accurately** from Android's own usage statistics, like Digital Wellbeing.
- **Builds** habits: streaks, month-over-month pace, a weekday matrix and a home-screen widget with a 30-day cycle (e.g. for contact lenses).

### Privacy

- The app has **no `INTERNET` permission**, so it has no way to send anything.
- No analytics, ads or telemetry.
- All data lives in a local SQLite database on the device and is excluded from cloud backups (`allowBackup=false`).
- Details: [privacy policy](PRIVACY.md).

### Install

**Prebuilt APK.** Download `detoxin-vX.Y.apk` from [Releases](https://github.com/vladvikhrov/detoxin/releases) and open it on your phone. Then enable the Detoxin accessibility service, grant usage access, allow "Display over other apps" and add the "Detoxin habit" widget. The app explains each permission on first launch.

**Build from source** (JDK 17+ and Android SDK):

```bash
git clone https://github.com/vladvikhrov/detoxin.git
cd detoxin
./scripts/generate-release-keystore.sh   # optional: your own signing key
./gradlew assembleRelease
```

Without `keystore.properties`, the release build is signed with the debug key, so a fresh clone builds as is.

### Support the author

Detoxin is free and ad-free. If it helped you, see the links in the Russian section above.

---

## Architecture · Архитектура

| Layer | Technology |
|---|---|
| Interception | `AccessibilityService`: DFS over the node tree (depth ≤ 8, `recycle()` on API < 34), CONFLATED scan channel, 1200 ms anti-loop suppression window |
| Screen time | `UsageStatsManager` sessions (per-activity tracking), cursor-based sync into Room with midnight splitting; hourly and daily limits |
| Storage | Room 2.6 + KSP, Flow, explicit migrations (no destructive fallback) |
| UI | Jetpack Compose, Material 3, bottom navigation |
| Charts | Pure Compose Canvas: stacked bars, monotone cubic Hermite splines (Fritsch–Carlson), heatmap, weekday frequency bars; geometry cached in `drawWithCache` |
| Widget | Glance 1.1.0 with a procedural 30-segment dial rendered via `android.graphics.Canvas` → `Bitmap` |
| Release | R8 minification and resource shrinking, APK ≈ 3.5 MB; tag-triggered GitHub Actions build |

```
app/src/main/java/com/detoxin/app/
├── core/            TrackedApp: limits for tracked apps
├── data/local/      Room: entities, DAO, DTO, migrations
├── service/         AccessibilityService, usage tracker, detectors
├── overlay/         Blocking overlay (WindowManager)
├── ui/analytics/    Screen time dashboard + Canvas charts
├── ui/habits/       Habit hub, stats, Canvas charts
├── ui/widget/       Glance widget, dial renderer, actions
└── util/            AppHealthMonitor, ExternalLinks
```

## License

Copyright 2026 vladvikhrov. Licensed under the [Apache License 2.0](LICENSE); redistributions must keep the [NOTICE](NOTICE) file.
