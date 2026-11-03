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

## Contrato de la API

`docs/contrato/restia-api.yaml` es una copia provisional del contrato, hasta que exista `restia-api`, que será su fuente. La app se desarrolla contra un servidor simulado, que no aplica reglas de negocio:

```bash
npx @stoplight/prism-cli@5.14.2 mock docs/contrato/restia-api.yaml -p 4010
```

## Forma de trabajo

Cada cambio probado se registra con un commit y se sube a este repositorio.
