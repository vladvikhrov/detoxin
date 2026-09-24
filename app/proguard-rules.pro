# ---------------------------------------------------------------------------
# Detoxin: правила R8 для release-сборки.
# ---------------------------------------------------------------------------

# Room: сущности, DTO и конвертеры отражаются на колонки SQLite по именам полей.
-keep class com.detoxin.app.data.local.entity.** { *; }
-keep class com.detoxin.app.data.local.dto.** { *; }
-keep class com.detoxin.app.data.local.converter.** { *; }

# Точки входа, которые создаёт система по имени класса из манифеста.
-keep class com.detoxin.app.service.DetoxinAccessibilityService { <init>(); }
-keep class com.detoxin.app.ui.widget.HabitGlanceWidgetReceiver { <init>(); }
-keep class com.detoxin.app.ui.widget.HabitWidgetConfigurationActivity { <init>(); }
-keep class com.detoxin.app.MainActivity { <init>(); }

# Glance создаёт ActionCallback рефлексией по имени класса (actionRunCallback<T>()).
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Не публиковать исходные имена файлов и номера строк в стек-трейсах release.
-renamesourcefileattribute SourceFile
