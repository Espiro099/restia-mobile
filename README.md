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

## Backend local

La versión debug usa `restia-api` en `http://127.0.0.1:8000/api/v1/`; la versión release usa HTTPS. En el repositorio de la API, con MongoDB corriendo, carga una sucursal de prueba y arranca el servidor:

```bash
php artisan restia:install --branch-code=CENTRO --branch-name="Restia Centro" --email=admin@demo.mx --name="Admin Demo"
php artisan restia:demo --branch=CENTRO --staff
php artisan restia:demo-printing --branch=CENTRO
php artisan restia:demo-mobile --branch=CENTRO
php artisan serve
```

`restia:demo-mobile` abre un turno de caja, configura la impresión de precuenta y recibo, e imprime el código de alta del dispositivo. Se puede repetir para obtener otro código. `restia:install` pide la contraseña del administrador; `mesero@demo.mx`, `cocina@demo.mx` y `gerente@demo.mx` usan la de `RESTIA_DEMO_PASSWORD`. No dejes `RESTIA_ADMIN_PASSWORD` en el `.env`: rompe una prueba de instalación del backend.

Cada vez que inicies el emulador o conectes un dispositivo, redirige el puerto. No se usa `10.0.2.2`, porque Android 17 lo trata como red local y exigiría el permiso `ACCESS_LOCAL_NETWORK`:

```bash
adb reverse tcp:8000 tcp:8000
```

Si la app ya se dio de alta contra otro servidor, borra sus datos (`adb shell pm clear com.restia.mobile`) para volver a registrarla.

## Contrato de la API

`docs/contrato/restia-api.yaml` es una copia del contrato; la fuente es `openapi/restia-api.yaml` en `restia-api`. Para trabajar sin backend, el servidor simulado no aplica reglas de negocio:

```bash
npx @stoplight/prism-cli@5.14.2 mock docs/contrato/restia-api.yaml -p 4010
```

Con `restia.apiBaseUrl=http://127.0.0.1:4010/` en `local.properties`, la versión debug lo usa en lugar del backend local; redirige el puerto 4010.

## Forma de trabajo

Cada cambio probado se registra con un commit y se sube a este repositorio.
