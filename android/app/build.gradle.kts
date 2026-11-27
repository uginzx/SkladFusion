import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Название, пакет, адрес сервера и версия — в sklad.properties (поверх — sklad.local.properties и -P<имя>=…).
val sklad = Properties().apply {
    listOf("sklad.properties", "sklad.local.properties").map { rootProject.file(it) }.filter { it.exists() }
        .forEach { f -> f.reader(Charsets.UTF_8).use { load(it) } }
}
fun sklad(name: String, default: String = ""): String =
    ((project.findProperty(name) as String?) ?: sklad.getProperty(name) ?: default).trim()

val appName = sklad("appName", "SkladFusion")
val appId = sklad("appId", "app.skladfusion")
val serverUrl = sklad("server").trimEnd('/')
val serverEditable = sklad("serverEditable", "true").toBoolean()
if (serverUrl.isNotEmpty() && !serverUrl.startsWith("https://") && !serverUrl.startsWith("http://")) {
    throw GradleException("server в sklad.properties — полный адрес, например https://sklad.example.com")
}
fun kstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.skladfusion"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = sklad("versionCode", "1").toInt()
        versionName = sklad("versionName", "1.0.0")
        buildConfigField("String", "SERVER", kstr(serverUrl))
        buildConfigField("boolean", "SERVER_EDITABLE", serverEditable.toString())
        buildConfigField("String", "APP_NAME", kstr(appName))
        manifestPlaceholders["appLabel"] = appName
        manifestPlaceholders["cleartext"] = "false"
    }

    signingConfigs {
        // Ключ подписи — свой у каждой организации, в git не кладётся: android/keystore.properties + файл .jks
        // (см. docs/android.md).
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Отладка на эмуляторе с локальным сервером: ./gradlew assembleDebug -Pserver=http://10.0.2.2:8088
            // (http:// разрешён только в отладочной сборке).
            applicationIdSuffix = ".debug"
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Без keystore.properties релиз подписывается отладочным ключом: годится для проверки, не для выкладки
            // (publish.sh без ключа не запустится).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// jvmTarget Kotlin берётся из compileOptions.targetCompatibility (встроенный Kotlin AGP 9).

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camera = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-view:$camera")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
