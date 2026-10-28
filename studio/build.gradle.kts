import java.util.Properties

plugins {
    alias(libs.plugins.android.application)

}

// ---- Firma compartida entre las dos PC -------------------------------------
// Las dos computadoras firman con la MISMA llave (keystore/jfr-upload.jks), así el APK
// de cualquiera de las dos se instala encima del otro: no hay que desinstalar, no se
// pierden los ajustes y no hay variantes ".dev".
// Esos dos archivos NO están en GitHub: se copian a mano una sola vez en cada PC.
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
    namespace = "com.jfrmedia.switcher.studio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jfrmedia.switcher.studio"
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
        // debug y release usan el MISMO paquete y la MISMA firma:
        // lo que compile cualquier PC se instala encima de lo anterior.
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
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.webrtc)
    // Dibuja el código QR de emparejamiento (solo generar; no lee códigos).
    implementation("com.google.zxing:core:3.5.3")
    // Motor de transmisión RTMP/RTMPS (Facebook, YouTube, etc.)
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
    // Login oficial de Facebook (no permite WebView)
    implementation("com.facebook.android:facebook-login:17.0.0")
}
