import java.util.Properties

plugins {
    alias(libs.plugins.android.application)

}

// ---- Firma compartida entre las dos PC -------------------------------------
// Misma llave en las dos computadoras (keystore/jfr-upload.jks): el APK de una PC se
// instala encima del de la otra sin desinstalar y sin variantes ".dev".
// Los dos archivos de la llave NO están en GitHub: se copian a mano una vez por PC.
val llave = Properties().apply {
    val archivo = rootProject.file("keystore.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
val llaveLista = llave.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true
if (!llaveLista) {
    throw GradleException(
        """
        |Falta la llave de firma compartida: no se puede compilar.
        |Copia a la carpeta del proyecto estos dos archivos (te los pasa Jorge):
        |    keystore\jfr-upload.jks
        |    keystore.properties
        |Sin ellos cada PC firmaría distinto y habría variantes otra vez.
        """.trimMargin()
    )
}

android {
    namespace = "com.jfrmedia.switcher.cam"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jfrmedia.switcher.cam"
        minSdk = 26
        targetSdk = 36
        // La versión se cambia en un solo lugar: gradle.properties (jfr.versionName / jfr.versionCode)
        versionCode = providers.gradleProperty("jfr.versionCode").get().toInt()
        versionName = providers.gradleProperty("jfr.versionName").get()

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("compartida") {
            storeFile = rootProject.file(llave.getProperty("storeFile"))
            storePassword = llave.getProperty("storePassword")
            keyAlias = llave.getProperty("keyAlias")
            keyPassword = llave.getProperty("keyPassword")
        }
    }

    buildTypes {
        // debug y release usan el MISMO paquete y la MISMA firma.
        debug {
            signingConfig = signingConfigs.getByName("compartida")
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("compartida")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.webrtc)
    // Lector de QR con pantalla propia (no depende de Google Play Services).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
