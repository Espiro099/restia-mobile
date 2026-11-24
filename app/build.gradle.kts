import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.restia.mobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.restia.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Firma de producción: la llave vive fuera del repositorio (docs/Firma_Release.md). Se toma de local.properties
    // (restia.release.*) o de variables de entorno (RESTIA_RELEASE_*, para CI). Sin ella, release sale sin firmar.
    val releaseStore = signingValue("restia.release.storeFile", "RESTIA_RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signingValue("restia.release.storePassword", "RESTIA_RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("restia.release.keyAlias", "RESTIA_RELEASE_KEY_ALIAS") ?: "restia"
                keyPassword = signingValue("restia.release.keyPassword", "RESTIA_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // restia-api local por adb reverse. 10.0.2.2 requeriría ACCESS_LOCAL_NETWORK en Android 17.
            // Para el servidor simulado: restia.apiBaseUrl=http://127.0.0.1:4010/ en local.properties.
            buildConfigField("String", "API_BASE_URL", "\"${localProperty("restia.apiBaseUrl") ?: "http://127.0.0.1:8000/api/v1/"}\"")
            // Anclaje opcional en debug: con el proxy TLS local (restia-api/deploy/apache) se pone
            // restia.apiBaseUrl=https://127.0.0.1:8443/api/v1/ y restia.apiPins=<huella SPKI>.
            buildConfigField("String", "API_CERT_PINS", "\"${localProperty("restia.apiPins") ?: ""}\"")
            buildConfigField("int", "API_TIMEOUT_SECONDS", "15")
        }
        // Ambiente de pruebas publicado en Render (restia-api/docs/Despliegue_Staging.md). Se instala junto a la
        // de debug. Las huellas son de la intermedia WE1 y la raíz GTS Root R4 de Google Trust Services, que
        // firman los certificados de onrender.com; el certificado final cambia en cada renovación.
        create("staging") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging"
            // Hereda de debug, pero no se puede depurar: con ADB no se podría leer su sesión (run-as).
            isDebuggable = false
            versionNameSuffix = "-staging"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "API_BASE_URL", "\"https://restia-api-staging.onrender.com/api/v1/\"")
            buildConfigField("String", "API_CERT_PINS", "\"sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=,sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=\"")
            // El plan gratuito se duerme: la primera petición puede tardar cerca de un minuto.
            buildConfigField("int", "API_TIMEOUT_SECONDS", "60")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // Con el dominio definitivo se pone restia.releaseApiBaseUrl; mientras, el nombre reservado.
            val releaseUrl = localProperty("restia.releaseApiBaseUrl") ?: providers.gradleProperty("restia.releaseApiBaseUrl").orNull ?: "https://api.restia.com/api/v1/"
            require(releaseUrl.startsWith("https://")) { "restia.releaseApiBaseUrl debe ser HTTPS" }
            buildConfigField("String", "API_BASE_URL", "\"$releaseUrl\"")
            // Obligatorio en release: huella actual y de respaldo del certificado de api.restia.com.
            buildConfigField("String", "API_CERT_PINS", "\"${localProperty("restia.releaseApiPins") ?: providers.gradleProperty("restia.releaseApiPins").orNull ?: ""}\"")
            buildConfigField("int", "API_TIMEOUT_SECONDS", "15")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    // Solo para dibujar el código QR del enlace de alta; sin red ni permisos.
    implementation(libs.zxing.core)
    // Fotos del menú, con el cliente de ImageClient (sin token de sesión).
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Inicio de sesión con Google (Decisiones_Google_Login en restia-api): el ID token lo verifica la API.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

/** Dato de la firma: primero local.properties, luego la variable de entorno (CI). */
fun signingValue(property: String, env: String): String? = localProperty(property) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)?.takeIf { it.isNotBlank() }
}
