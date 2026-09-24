Ты — Principal Android Architect. Твоя задача — реализовать слой локальных данных (Offline-first, 100% приватность) для приложения Detoxin (minSdk 29, targetSdk 34) на Kotlin.

СТРОГИЕ ПРАВИЛА:
1. Запрещены заглушки ("// TODO", "/* реализуйте сами */", "...") и пропуски кода. Все классы, схемы таблиц, DAO и методы должны быть написаны целиком.
2. Сеть запрещена: в проекте отсутствует разрешение android.permission.INTERNET.
3. Стек: Room 2.6+ (KSP), Kotlin Coroutines + Flow, нативный Java Time API (LocalDate, Instant).

РЕАЛИЗУЙ:

1. Entity:
   - `AppUsageLog`:
     * Поля: `id: Long = 0` (autoGenerate = true), `packageName: String`, `sessionStartTime: Long`, `durationMs: Long`, `dateEpochDay: Long`.
     * Индексы: `dateEpochDay`, `packageName`.
   - `BlockedAttempt`:
     * Поля: `id: Long = 0` (autoGenerate = true), `blockType: BlockType`, `timestamp: Long`, `dateEpochDay: Long`.
     * Индексы: `dateEpochDay`, `blockType`.
   - `Habit`:
     * Поля: `id: Long = 0` (autoGenerate = true), `title: String`, `targetDaysPerWeek: Int`, `colorHex: Long`, `createdAt: Long`.
   - `HabitLog`:
     * Поля: `id: Long = 0` (autoGenerate = true), `habitId: Long`, `dateEpochDay: Long`, `isCompleted: Boolean`.
     * Связи: ForeignKey к `Habit.id` с `onDelete = ForeignKey.CASCADE`.
     * Индексы: `habitId`, а также УНИКАЛЬНЫЙ составной индекс на `(habitId, dateEpochDay)`.

2. TypeConverters и Enums:
   - Enum `BlockType`: `SHORTS`, `VK_FEED`, `VK_CLIPS`, `TIKTOK_TIMEOUT`.
   - TypeConverters для безопасного двустороннего сохранения `BlockType` в String/Int.

3. DTO и Агрегирующие DAO:
   - Модели данных (DTO):
     * `PackageUsageSummary(val packageName: String, val totalDurationMs: Long)`
     * `DailyBlockCount(val dateEpochDay: Long, val blockType: BlockType, val count: Int)`
     * `HabitWithLogs(val habit: Habit, val logs: List<HabitLog>)`
   - `UsageDao`:
     * Вставка одиночной записи и пачки логов.
     * Запрос суммарного времени по приложениям за интервал `[startEpochDay, endEpochDay]` с группировкой по packageName (возвращает `Flow<List<PackageUsageSummary>>`).
   - `BlockedAttemptDao`:
     * Вставка одиночной записи и пачки логов (`List<BlockedAttempt>`).
     * Запрос подсчета блокировок по дням и типам за интервал дат (возвращает `Flow<List<DailyBlockCount>>`).
   - `HabitDao`:
     * Базовые CRUD-операции для `Habit`.
     * Атомарный Toggle привычки: вставка записи со статусом true или инверсия `isCompleted = NOT isCompleted`, если запись за указанный день уже существует.
     * `@Transaction` запрос получения привычек со всеми их логами за интервал `[startEpochDay, endEpochDay]` (возвращает `Flow<List<HabitWithLogs>>`).

4. База данных и Thread-Safe Singleton:
   - `DetoxinDatabase : RoomDatabase` со всеми сущностями, версией 1 и `exportSchema = false`.
   - `DatabaseProvider`: потокобезопасный синглтон на основе Double-Checked Locking (с аннотацией `@Volatile`) для инициализации и получения инстанса Room системными компонентами (AccessibilityService, Glance Widget) без тяжелых DI-библиотек.

Предоставь чистый, типобезопасный и компилируемый Kotlin-код с архитектурными пояснениями на русском языке.