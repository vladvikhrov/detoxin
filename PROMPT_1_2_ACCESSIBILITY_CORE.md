Ты — Principal Android Architect. Слой данных Room реализован. Твоя задача — написать отказоустойчивое ядро перехвата и сервис блокировки для приложения Detoxin (minSdk 29, targetSdk 34) на Kotlin.

СТРОГИЕ ПРАВИЛА:
1. Запрещены заглушки, эллипсисы и комментарии вроде "// TODO: допишите логику". Весь код должен быть готов к сборке.
2. Изоляция главного потока: `onAccessibilityEvent` работает на UI Thread. Анализ дерева нод обязан передаваться в фоновую корутину (`Dispatchers.Default`), а запись в базу — через буфер на `Dispatchers.IO`. Никаких блокировок главного потока.
3. Предотвращение утечек памяти (Memory Leaks): для всех версий Android ниже API 34 промежуточные экземпляры `AccessibilityNodeInfo` при рекурсивном обходе ОБЯЗАНЫ утилизироваться через `.recycle()`.
4. Защита от петли возврата (Anti-Loop): после срабатывания `performGlobalAction(GLOBAL_ACTION_BACK)` активируется окно подавления (Suppression Window) на 1200 мс, игнорирующее события от целевого пакета, пока закрываемый экран анимируется.

РЕАЛИЗУЙ:

1. Конфигурация:
   - Фрагмент `AndroidManifest.xml` с регистрацией `DetoxinAccessibilityService` и системными разрешениями (`BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`).
   - `res/xml/detoxin_accessibility_config.xml` с флагами `flagReportViewIds`, `flagRetrieveInteractiveWindows` и фильтром пакетов: "com.google.android.youtube", "com.vkontakte.android", "com.zhiliaoapp.musically".

2. Буфер логов (LogEventEngine):
   - Потокобезопасный репозиторий на базе `Channel<BlockedAttempt>(capacity = Channel.BUFFERED)`.
   - Фоновый сборщик (batch drain), сбрасывающий накопленные записи в Room через `DatabaseProvider` пачками раз в 2 секунды или при накоплении 10 событий на `Dispatchers.IO`.

3. `DetoxinAccessibilityService`:
   - Безопасный рекурсивный обход дерева нод (DFS) с жестким лимитом глубины (не более 8 уровней) и гарантированным `.recycle()` для всех дочерних узлов на SDK < 34.
   - Эвристика YouTube Shorts:
     * Определение контейнера Shorts без привязки к обфусцированным ID: проверка `className` в сочетании с мультиязычным `contentDescription` ("Shorts", "Шортс", "Короткие видео") или маркерами навигационной панели.
     * При детекте: вызов `performGlobalAction(GLOBAL_ACTION_BACK)`, включение окна подавления на 1200 мс и отправка события `BlockType.SHORTS` в `LogEventEngine`.
   - Эвристика VKontakte:
     * Перехват открытых разделов «Клипы» и «Лента».
     * КРИТИЧЕСКИ ВАЖНО: валидировать атрибут `isSelected == true` или `isFocused == true` на нижних вкладках либо дескрипторах активного экрана, чтобы НЕ блокировать работу мессенджера (вкладка «Чаты») и профиля.
     * При подтверждении — `GLOBAL_ACTION_BACK` + отправка в `LogEventEngine`.
   - Движок сессий TikTok (Session & Cooldown Engine):
     * Фиксация активного фокуса окна TikTok с замером времени через `SystemClock.elapsedRealtime()` и периодический корутин-таймер (каждую секунду).
     * Сохранение отрезков сессий в `AppUsageLog`.
     * При превышении порога в 30 минут непрерывного использования — запуск блокировки через `OverlayManager`.
   - Корректная очистка ресурсов в `onDestroy` (отмена корутин, закрытие каналов).

4. `OverlayManager`:
   - Полноэкранный системный оверлей через `WindowManager` с типом `TYPE_APPLICATION_OVERLAY`.
   - Блокирующее окно с неотменяемым обратным отсчетом времени отдыха (Cooldown: 10 минут), перехватывающее все тач-события.
   - Безопасное удаление View из `WindowManager` по окончании таймера или в `onDestroy` сервиса.

Предоставь чистый, компилируемый Kotlin-код с архитектурными комментариями на русском языке.