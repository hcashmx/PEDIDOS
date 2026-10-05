import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val effyFlowVersion = providers.gradleProperty("appVersion").orElse("0.9.0")
val effyFlowDescription = "Sistema de captura y sincronización de pedidos"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    id("app.cash.sqldelight") version "2.0.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

kotlin {
//    androidTarget { compilerOptions {
//            jvmTarget.set(JvmTarget.JVM_11)
//        }
//    }
    androidTarget()

//    listOf(
//        iosArm64(),
//        iosSimulatorArm64()
//    ).forEach { iosTarget ->
//        iosTarget.binaries.framework {
//            baseName = "ComposeApp"
//            isStatic = true
//        }
//    }

    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.0.2")
            implementation("io.ktor:ktor-client-okhttp:2.3.8")
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)

//            implementation("net.zetetic:android-database-sqlcipher:4.5.4@aar")
//            implementation("androidx.sqlite:sqlite:2.4.0")

        }
        commonMain.dependencies {

            implementation("com.itextpdf:itext7-core:7.2.5")
            implementation("com.google.zxing:core:3.5.3")
            implementation("com.google.zxing:javase:3.5.3")

            // Networking - Ktor
            implementation("io.ktor:ktor-client-core:2.3.8")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
            implementation("io.ktor:ktor-client-auth:2.3.11")
            // JSON
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

            // Corrutinas (ya vienen en compose, pero agregamos core por seguridad)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

            //SQLITE
            implementation("app.cash.sqldelight:runtime:2.0.2")
            implementation("io.github.aakira:napier:2.6.1")
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.1")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("com.benasher44:uuid:0.8.4")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation("io.ktor:ktor-client-mock:2.3.11")
        }
        jvmMain.dependencies {
            implementation("org.apache.pdfbox:pdfbox:3.0.6")
            implementation("org.apache.pdfbox:pdfbox-tools:3.0.6")
            implementation("app.cash.sqldelight:jdbc-driver:2.0.2") // 👈 este es el correcto
            implementation("org.xerial:sqlite-jdbc:3.45.3.0")        // 👈 driver real de SQLite
            implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            implementation("io.ktor:ktor-client-cio:2.3.8")
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

//            implementation("org.xerial:sqlite-jdbc:3.46.0.0")
//            implementation("com.zetetic:sqlcipher:4.5.4")
//            implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
        }
    }
}

sqldelight {
    databases {
        create("EffyDatabase") {
            packageName.set("com.cyssoftware.effyflow.db")
        }
    }
}

android {
    namespace = "com.cyssoftware.effyflow"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.cyssoftware.effyflow"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.cyssoftware.effyflow.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "EffyFlow"
            packageVersion = effyFlowVersion.get()
            description = effyFlowDescription
            windows (){
                //iconFile.set(project.file("src/main/resources/icon.ico"))

                menu = true                     // Agregar al menú inicio
                shortcut = true                 // Crear acceso directo en el escritorio
                upgradeUuid = "12345678-abcd-ef12-3456-abcdef123456" // Cualquier UUID único
                perUserInstall = true

            }
        }
    }
}
