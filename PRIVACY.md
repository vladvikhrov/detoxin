# Политика конфиденциальности Detoxin · Privacy Policy

Действует с 11 октября 2026 г., обновлено 10 ноября 2026 г. (версия 1.4.0) · Effective October 11, 2026, updated November 10, 2026 (version 1.4.0)

## Коротко

Detoxin **не имеет доступа в интернет** (в приложении нет разрешения `android.permission.INTERNET`), не содержит аналитики, рекламы и сторонних SDK для сбора данных. Все данные создаются и хранятся **только на вашем устройстве**. Разработчик не получает, не хранит и не обрабатывает ваши данные.

## Какие данные хранятся на устройстве

| Данные | Зачем | Где |
|---|---|---|
| Привычки: название, цвет, норма в неделю, дата создания, начало 30-дневного цикла, признак автоотметки «Лёг вовремя», тип «один или два раза в день» | Трекер привычек и виджет | Локальная база SQLite приложения |
| Отметки выполнения привычек по дням (в том числе поставленные задним числом и автоматические; у двухразовых привычек — отдельно утро и вечер) | Стрики, статистика, виджет | Локальная база SQLite |
| Периоды, когда служба защиты была выключена: только время начала и конца | Итог «Защита была выключена…», статистика ночей, автопривычка «Лёг вовремя» | Локальная база SQLite |
| Настройки режима «Отбой» (время отбоя и подъёма, ночной лимит) и метка последней активности службы (раз в минуту, пока служба работает) | Ночные ограничения; распознавание случаев, когда процесс службы был остановлен | Локальные настройки приложения (DataStore) |
| Длительность сессий TikTok и YouTube (приложение, время начала, длительность, дата) — переносится из системной статистики Android | Экранное время, часовые и суточные лимиты | Локальная база SQLite |
| Факты блокировок (тип: Shorts, «Клипы», «Лента», лимит TikTok, сайт TikTok или YouTube Shorts в браузере, экран установки TikTok, YouTube или Яндекс Музыки; время). Адрес страницы не сохраняется | График «Спасённые открытия», Detox Score, «Стена стыда» | Локальная база SQLite |
| Хэш мастер-PIN (PBKDF2 с солью; сам PIN не хранится), переключатели полной блокировки, время экстренной разблокировки, счётчик неверных попыток | Экран «Защита»: выключить блокировку можно только после PIN-кода и контрольной фразы | Локальные настройки приложения (DataStore) |
| Привязка виджета к привычке | Работа виджета на рабочем столе | Локальное хранилище виджета |
| Отметка «объяснение разрешений прочитано» и момент последней синхронизации статистики | Не показывать вводный экран повторно; не считать время дважды | Локальные настройки приложения |

Данные исключены из облачных резервных копий Android (`allowBackup="false"`) и **удаляются вместе с приложением** (или через «Настройки → Приложения → Detoxin → Хранилище → Очистить данные»).

## Служба специальных возможностей (AccessibilityService)

Служба включается только вами в настройках Android и нужна для основной функции приложения — защиты от бесконечных лент.

- **Что видит:** Android присылает службе события всех приложений, но Detoxin сразу отбрасывает события любого приложения не из своего списка, проверяя только системное имя пакета. Разбираются элементы интерфейса (подписи вкладок, описания элементов, идентификаторы экранов) только в таких приложениях:
  - YouTube (`com.google.android.youtube`), ВКонтакте (`com.vkontakte.android`), TikTok (`com.zhiliaoapp.musically` и региональные сборки) и Яндекс Музыка (`ru.yandex.music`, только чтобы закрыть её при полной блокировке);
  - браузеры (Chrome, Firefox, Яндекс Браузер, Opera, Edge, Brave, Vivaldi, Samsung Internet, браузеры Huawei, Honor и Xiaomi и другие): читается **только текст адресной строки**, чтобы узнать сайт TikTok или раздел YouTube Shorts. Содержимое страниц не читается, адреса не сохраняются;
  - магазины приложений и установщики пакетов (Google Play, RuStore, AppGallery, магазин Honor, GetApps, Aurora, F-Droid, системный установщик): читаются названия на экране, чтобы узнать страницу установки TikTok, YouTube или Яндекс Музыки.
- **Зачем:** закрыть TikTok, YouTube и Яндекс Музыку, если для них включена полная блокировка, и экран их установки; закрыть сайт TikTok и YouTube Shorts в браузере; распознать открытые разделы Shorts, «Клипы» и «Лента» и закрыть их: в YouTube служба нажимает системную кнопку «Назад», во ВКонтакте — нажимает вкладку «Мессенджер» (если вкладку найти не удалось, просит само приложение ВК открыть раздел сообщений по ссылке `vk.com/im`); показывать экран лимита.
- **Проверка активного приложения:** пока открыт TikTok или YouTube, служба раз в секунду узнаёт системное имя (package name) приложения на экране, чтобы понять, когда вы из него вышли. Содержимое других приложений не читается.
- **Что не делается:** тексты сообщений, комментарии, контакты, видео, пароли и любые данные других приложений не читаются, не сохраняются и не передаются. Анализ экрана происходит в оперативной памяти и сразу отбрасывается; сохраняется только тип блокировки и время.

## Доступ к истории использования (PACKAGE_USAGE_STATS)

Специальный доступ, который включается только вами в «Настройки → Доступ к истории использования».

- **Что читает:** системную статистику Android о том, когда открывались приложения (те же данные, что показывает «Цифровое благополучие»).
- **Что использует:** только время открытия и закрытия TikTok и YouTube — для экранного времени, лимитов (TikTok — 15 минут в час и 1 час в день, YouTube — 1.5 часа в день, режим «Отбой»), графиков и итога за период, когда защита была выключена. События других приложений при чтении пропускаются и не сохраняются.
- **Зачем:** система ведёт статистику постоянно, поэтому время не теряется, даже если прошивка остановит службу специальных возможностей ради экономии батареи.

## Остальные разрешения

| Разрешение | Назначение |
|---|---|
| `SYSTEM_ALERT_WINDOW` (показ поверх других приложений) | Экран отдыха при исчерпании дневного лимита |
| `VIBRATE` | Короткий отклик при касании виджета |
| `POST_NOTIFICATIONS` (Android 13+, запрашивается при включении «Отбоя») | Напоминание за 30 минут до отбоя и итог периода, когда защита была выключена. Уведомления формируются на устройстве |
| `RECEIVE_BOOT_COMPLETED` | Заново поставить будильники «Отбоя» после перезагрузки |
| `WAKE_LOCK`, `FOREGROUND_SERVICE` | Добавлены системной библиотекой AndroidX WorkManager (используется виджетами Glance) для корректного обновления виджета; сетевого доступа не дают |

Разрешение `ACCESS_NETWORK_STATE`, которое библиотека добавляет по умолчанию, из приложения намеренно удалено.

## Файлы, которые создаёте вы

- **Карточка итогов** сохраняется по вашей команде как PNG в папку `Pictures/Detoxin` через MediaStore. Разрешение на доступ к хранилищу не нужно; файл никуда не отправляется.
- **Резервная копия базы:** экспорт и импорт идут через системный диалог выбора файла, место выбираете вы. Файл содержит все данные из таблицы выше; храните его так же бережно, как сам телефон.

## Внешние ссылки

Кнопки «Исходный код» и «Поддержать разработчика» открывают страницу в **вашем браузере**. Само приложение при этом в сеть не обращается; к открытой странице применяется политика соответствующего сайта.

## Дети

Приложение не предназначено специально для детей и не собирает данные ни о ком.

## Изменения и контакты

Изменения этой политики публикуются в репозитории: https://github.com/vladvikhrov/detoxin/blob/main/PRIVACY.md
Вопросы — через Issues: https://github.com/vladvikhrov/detoxin/issues

---

## Summary (English)

Detoxin has **no Internet permission**, no analytics, ads or tracking SDKs. Habits, habit check-ins (including backfilled and automatic ones), Bedtime settings, start/end timestamps of periods when the protection service was off, TikTok/YouTube session durations (imported from Android's own usage statistics via the usage-access permission you grant) and block events are stored **only on your device** in a local database, excluded from cloud backups and deleted when you uninstall the app. The developer receives no data.

The accessibility service, enabled only by you, receives events from all apps but immediately drops any event whose package is not on its list. It inspects on-screen UI elements only in YouTube, VK, TikTok and Yandex Music (to detect Shorts/Clips/Feed and close them, show the limit screen, or close fully blocked apps), in known browsers (**only the address bar text**, to close the TikTok site and YouTube Shorts; page contents are not read and URLs are not stored) and in app stores and package installers (to close the TikTok/YouTube/Yandex Music install page). While TikTok or YouTube is open it checks once per second which app (package name only) is in the foreground. Messages, comments, passwords and data of other apps are never read, stored or transmitted.

Version 1.4.0 also stores, on the device only: the habit type (once or twice a day) with morning/evening check-ins, block events for browser and install-page blocks (without URLs), and for the Protection screen a salted PBKDF2 hash of your master PIN (the PIN itself is never stored), full-block toggles and the emergency-unlock deadline. The summary card is saved, when you ask, as a PNG to `Pictures/Detoxin`; database backups are exported and imported through the system file picker to a location you choose.

Other permissions: `SYSTEM_ALERT_WINDOW` for the rest screen, `VIBRATE` for widget haptics, `POST_NOTIFICATIONS` (Android 13+, asked when Bedtime is enabled) for the bedtime reminder and the protection-off report, `RECEIVE_BOOT_COMPLETED` to restore Bedtime alarms after a reboot; `WAKE_LOCK` and `FOREGROUND_SERVICE` are added by AndroidX WorkManager for widget updates. Links to the source code and donations open in your own browser.

Contact: https://github.com/vladvikhrov/detoxin/issues
