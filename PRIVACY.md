# Политика конфиденциальности Detoxin · Privacy Policy

Действует с 25 сентября 2026 г. · Effective September 25, 2026

## Коротко

Detoxin **не имеет доступа в интернет** (в приложении нет разрешения `android.permission.INTERNET`), не содержит аналитики, рекламы и сторонних SDK для сбора данных. Все данные создаются и хранятся **только на вашем устройстве**. Разработчик не получает, не хранит и не обрабатывает ваши данные.

## Какие данные хранятся на устройстве

| Данные | Зачем | Где |
|---|---|---|
| Привычки: название, цвет, норма в неделю, дата создания, начало 30-дневного цикла | Трекер привычек и виджет | Локальная база SQLite приложения |
| Отметки выполнения привычек по дням | Стрики, статистика, виджет | Локальная база SQLite |
| Длительность сессий TikTok и YouTube (приложение, время начала, длительность, дата) — переносится из системной статистики Android | Экранное время, часовые и суточные лимиты | Локальная база SQLite |
| Факты блокировок (тип: Shorts, «Клипы», «Лента», лимит TikTok; время) | График «Спасённые открытия» | Локальная база SQLite |
| Привязка виджета к привычке | Работа виджета на рабочем столе | Локальное хранилище виджета |
| Отметка «объяснение разрешений прочитано» и момент последней синхронизации статистики | Не показывать вводный экран повторно; не считать время дважды | Локальные настройки приложения |

Данные исключены из облачных резервных копий Android (`allowBackup="false"`) и **удаляются вместе с приложением** (или через «Настройки → Приложения → Detoxin → Хранилище → Очистить данные»).

## Служба специальных возможностей (AccessibilityService)

Служба включается только вами в настройках Android и нужна для основной функции приложения — защиты от бесконечных лент.

- **Что видит:** элементы интерфейса (подписи вкладок, описания элементов, идентификаторы экранов) только в приложениях YouTube (`com.google.android.youtube`), ВКонтакте (`com.vkontakte.android`) и TikTok (`com.zhiliaoapp.musically`).
- **Зачем:** распознать открытые разделы Shorts, «Клипы» и «Лента» и закрыть их: в YouTube служба нажимает системную кнопку «Назад», во ВКонтакте — нажимает вкладку «Мессенджер» (если вкладку найти не удалось, просит само приложение ВК открыть раздел сообщений по ссылке `vk.com/im`); показывать экран лимита.
- **Проверка активного приложения:** пока открыт TikTok или YouTube, служба раз в секунду узнаёт системное имя (package name) приложения на экране, чтобы понять, когда вы из него вышли. Содержимое других приложений не читается.
- **Что не делается:** тексты сообщений, комментарии, контакты, видео, пароли и любые данные других приложений не читаются, не сохраняются и не передаются. Анализ экрана происходит в оперативной памяти и сразу отбрасывается; сохраняется только тип блокировки и время.

## Доступ к истории использования (PACKAGE_USAGE_STATS)

Специальный доступ, который включается только вами в «Настройки → Доступ к истории использования».

- **Что читает:** системную статистику Android о том, когда открывались приложения (те же данные, что показывает «Цифровое благополучие»).
- **Что использует:** только время открытия и закрытия TikTok и YouTube — для экранного времени, лимитов (TikTok — 15 минут в час и 1 час в день, YouTube — 1.5 часа в день) и графиков. События других приложений при чтении пропускаются и не сохраняются.
- **Зачем:** система ведёт статистику постоянно, поэтому время не теряется, даже если прошивка остановит службу специальных возможностей ради экономии батареи.

## Остальные разрешения

| Разрешение | Назначение |
|---|---|
| `SYSTEM_ALERT_WINDOW` (показ поверх других приложений) | Экран отдыха при исчерпании дневного лимита |
| `VIBRATE` | Короткий отклик при касании виджета |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | Добавлены системной библиотекой AndroidX WorkManager (используется виджетами Glance) для корректного обновления виджета; сетевого доступа не дают |

Разрешение `ACCESS_NETWORK_STATE`, которое библиотека добавляет по умолчанию, из приложения намеренно удалено.

## Внешние ссылки

Кнопки «Исходный код» и «Поддержать разработчика» открывают страницу в **вашем браузере**. Само приложение при этом в сеть не обращается; к открытой странице применяется политика соответствующего сайта.

## Дети

Приложение не предназначено специально для детей и не собирает данные ни о ком.

## Изменения и контакты

Изменения этой политики публикуются в репозитории: https://github.com/vladvikhrov/detoxin/blob/main/PRIVACY.md
Вопросы — через Issues: https://github.com/vladvikhrov/detoxin/issues

---

## Summary (English)

Detoxin has **no Internet permission**, no analytics, ads or tracking SDKs. Habits, habit check-ins, TikTok/YouTube session durations (imported from Android's own usage statistics via the usage-access permission you grant) and block events are stored **only on your device** in a local database, excluded from cloud backups and deleted when you uninstall the app. The developer receives no data.

The accessibility service, enabled only by you, inspects on-screen UI elements **only** in YouTube, VK and TikTok to detect Shorts/Clips/Feed and close them (Back in YouTube; switching VK to its Messenger tab) and to show the limit screen; while one of them is open it checks once per second which app (package name only) is in the foreground. Messages, comments, passwords and data of other apps are never read, stored or transmitted.

Other permissions: `SYSTEM_ALERT_WINDOW` for the rest screen, `VIBRATE` for widget haptics; `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` are added by AndroidX WorkManager for widget updates. Links to the source code and donations open in your own browser.

Contact: https://github.com/vladvikhrov/detoxin/issues
