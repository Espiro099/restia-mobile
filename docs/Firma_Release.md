# Firma de la app de producción

La versión **release** (`com.restia.mobile`) se firma con una llave propia que **nunca entra al repositorio** (`*.jks` y `local.properties` están en `.gitignore`). Debug y staging siguen firmándose con la llave de debug de cada computadora.

## 1. Crear la llave (una sola vez)

En una computadora de confianza, con el JDK de Android Studio:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore C:\llaves\restia-release.jks -alias restia -keyalg RSA -keysize 4096 -validity 10000
```

- Pide una contraseña para el almacén y otra para la llave. Usa contraseñas largas y guárdalas en un gestor de contraseñas.
- Los datos del certificado (nombre, organización) pueden ser los de la empresa.
- **Respalda `restia-release.jks` y sus contraseñas en dos lugares seguros.** Si se pierde la llave, la app publicada ya no se puede actualizar con la misma firma.
- Si la publican en Google Play, activen **Play App Signing**. Esta llave queda como *llave de carga* y Google guarda la de firma. Así, perder la de carga tiene remedio.

## 2. Decirle a Gradle dónde está

En `local.properties`, que no se sube al repositorio:

```properties
restia.release.storeFile=C:/llaves/restia-release.jks
restia.release.storePassword=...
restia.release.keyAlias=restia
restia.release.keyPassword=...

# Cuando haya dominio (sin esto queda https://api.restia.com/api/v1/):
restia.releaseApiBaseUrl=https://api.dominio-de-la-mesa.com/api/v1/
# Huellas SPKI del certificado de la API: la actual y una de respaldo (obligatorias en release).
restia.releaseApiPins=sha256/...,sha256/...
```

En CI se usan variables de entorno en lugar del archivo: `RESTIA_RELEASE_STORE_FILE`, `RESTIA_RELEASE_STORE_PASSWORD`, `RESTIA_RELEASE_KEY_ALIAS` y `RESTIA_RELEASE_KEY_PASSWORD`, como secretos del repositorio.

Sin estos datos, `assembleRelease` genera `app-release-unsigned.apk`, que no se puede instalar.

## 3. Compilar

```powershell
./gradlew :app:bundleRelease
```

- Para Google Play: `app/build/outputs/bundle/release/app-release.aab`.
- Para instalar directo: `./gradlew :app:assembleRelease` genera `app/build/outputs/apk/release/app-release.apk`.

## 4. Huella SHA-1 para Google (inicio de sesión con Google)

```powershell
./gradlew :app:signingReport
```

Toma el `SHA1` de la variante **release** y crea en Google Cloud, en el mismo proyecto de Restia, un **cliente OAuth Android** con:

- Paquete: `com.restia.mobile`.
- Huella SHA-1: la de la llave de release.

Con Play App Signing, crea además otro cliente Android con la SHA-1 de la **llave de firma de la app** que muestra Play Console (Configuración → Integridad de la app). Esa es la que llevan las instalaciones desde la tienda.

La API no cambia: sigue usando el ID de cliente **Web** (`GOOGLE_CLIENT_ID`).
