# Mandala Pen

Aplicación Android (Kotlin + Jetpack Compose) para dibujar y colorear mandalas con lápiz óptico (stylus / S Pen) en tablets.

## Características

- Lienzo con simetría radial y espejo.
- Generador de mandalas en anillos concéntricos cerrados, listos para colorear.
- Galería de diseños y diálogos de gestión.
- Diseño adaptado a tablet.
- Interfaz en catalán y castellano.

## Requisitos

- Android Studio (reciente) con JDK 17+
- Android SDK: `minSdk 29`, `targetSdk 35`
- Dispositivo o emulador con Android 10+ (recomendado tablet con stylus)

## Compilar y ejecutar

```bash
./gradlew assembleDebug      # genera el APK de depuración
./gradlew installDebug       # instala en el dispositivo conectado
```

En Windows usa `gradlew.bat`. `local.properties` (ruta del SDK) se genera con Android Studio y no se versiona.

## Estructura

```
app/src/main/java/cat/mandala/pen/
├── MainActivity.kt   # punto de entrada
├── Board.kt          # lienzo y simetría
├── Generator.kt      # generador de mandalas
├── Designs.kt        # diseños
├── Gallery.kt        # galería
├── Dialogs.kt        # diálogos
├── TabletLayout.kt   # layout para tablet
└── Look.kt           # estilo visual
```

## Paquete

`cat.mandala.pen`
