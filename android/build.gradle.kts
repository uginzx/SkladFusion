plugins {
    // AGP 9: Kotlin встроен в плагин Android, отдельный org.jetbrains.kotlin.android не нужен.
    // Плагин Compose задаёт и версию Kotlin (Gradle выравнивает KGP по нему).
    id("com.android.application") version "9.5.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
