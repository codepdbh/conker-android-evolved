# Progreso

Léelo primero al retomar el trabajo. Cada línea dice si algo está **probado**, solo **compilado** o **escrito**.

## Niveles de aceptación

| Nivel | Estado |
|---|---|
| A. Estructura preparada | Hecho, salvo abrirlo en Android Studio (sin comprobar) |
| B. Host Android funcional | En curso: la APK compila; falta probarla en el teléfono |
| C. Recompilación integrada | Compilado con una copia no verificada: el XBE genera C++, compila a ARM64 y enlaza |
| D. Primer arranque jugable | **Sin probar**: el juego no se ha ejecutado nunca en este port |
| E. Port estable | Sin empezar |

## Registro

### 2026-11-02

Comprobado:

- La carpeta solo contenía la ISO; `Conker-Android-Evolved-Starter/` no existía, así que no había código que conservar.
- La base es la rama `android` de Mabec-games/live-and-recompiled, commit `25e614d`. Su README dice que no se ha probado en
  ningún dispositivo.
- Redmi Note 8 (`ginkgo`, Adreno 610): datos leídos por adb, en [COMPATIBILITY.md](COMPATIBILITY.md).
- `tests/test_game_tools.py`: 11 tests pasan, con datos sintéticos.
- `libmain.so` compila y enlaza para AArch64 (44,6 MB, 4 min con 11 hilos).
- Las 10 instrucciones LSE de `libmain.so` están en los atómicos *outline* de Zig, detrás de un salto que siempre las evita.
  No hay `ldapr` ni `dot`.
- APK de depuración completa generada (38,6 MB).

Probado en el Redmi Note 8, con la APK solo-launcher:

- El launcher abre; la sonda lee CPU y Vulkan y la pantalla de compatibilidad muestra el resultado.
- El informe genera un zip válido, con logcat limitado a esta app, y abre el selector de Android. No se envió.
- El editor del HUD abre y coincide con la disposición por defecto. La barra tapaba el botón A: movida al centro, recompilada,
  **sin volver a ver en pantalla** porque la reinstalación quedó en el diálogo de Play Protect.

Escrito, sin ejecutar en un teléfono:

- Alternativas Vulkan 1.1 del renderer ([GRAPHICS.md](GRAPHICS.md)).
- Extracción de la ISO en Kotlin.
- En el editor: arrastrar, guardar y la prueba multitáctil. En el juego: `touch.cpp` con el diseño guardado.
- Valores por defecto en móvil: 30 FPS y render al 50 %.

Publicado:

- Código fuente en GitHub, a petición del usuario. `assets/`, `generated/` y `out/` quedan fuera.

Bloqueado:

- Instalar la APK con `libmain.so`. Play Protect muestra un diálogo en cada instalación y el teléfono tiene
  `upload_apk_enable=1`. Esa APK contiene código derivado del juego: no instalarla hasta que el usuario decida. No se cambian sus
  ajustes de seguridad desde adb.

## Errores encontrados y su solución

| Error | Causa | Solución |
|---|---|---|
| `ld.lld: cannot open .../28\crtbegin_dynamic.o` al configurar en Windows | `file(WRITE)` de CMake escribe CRLF en Windows y Zig toma el CR como parte de la ruta | `file(CONFIGURE ... NEWLINE_STYLE UNIX)` en `cmake/zig-android.cmake` |
| `ld.lld: cmake_pch.h.o: unknown file type` | `zig cc` pasa el encabezado precompilado de SDL al enlazador | `CMAKE_DISABLE_PRECOMPILE_HEADERS` en `cmake/zig-android.cmake` |
| Un `adb install` que no termina | Diálogo de Play Protect en el teléfono | Responder en el teléfono; no se puede resolver desde el PC sin desactivar la protección |

## Siguiente paso

1. Instalar la APK completa e instalar el juego desde el launcher.
2. Lanzar el juego y leer sus logs con `scripts/collect_logs.ps1`.
3. Corregir lo que falle, en orden de arranque: carga de `libmain.so`, memoria, Vulkan, shaders, primer fotograma.
