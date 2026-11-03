# Restia Mobile

Aplicación Android de Restia para empleados, en Kotlin y Jetpack Compose. La web y la API viven en sus propios repositorios.

## Requisitos

- Android Studio con su JDK incluido.
- Android SDK 37.

## Compilar y probar

Abre la carpeta en Android Studio, o desde una terminal:

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

Con un emulador abierto, para probar el cifrado en Android Keystore:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Contrato de la API

`docs/contrato/restia-api.yaml` es una copia provisional del contrato, hasta que exista `restia-api`, que será su fuente. La app se desarrolla contra un servidor simulado, que no aplica reglas de negocio. La versión debug lo busca en `http://127.0.0.1:4010/`; la versión release usa HTTPS:

```bash
npx @stoplight/prism-cli@5.14.2 mock docs/contrato/restia-api.yaml -p 4010
```

Cada vez que inicies el emulador o conectes un dispositivo, redirige el puerto. No se usa `10.0.2.2`, porque Android 17 lo trata como red local y exigiría el permiso `ACCESS_LOCAL_NETWORK`:

```bash
adb reverse tcp:4010 tcp:4010
```

## Forma de trabajo

Cada cambio probado se registra con un commit y se sube a este repositorio.
