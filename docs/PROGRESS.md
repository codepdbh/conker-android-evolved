# Progreso

Léelo primero al retomar el trabajo. Cada línea dice si algo está **probado**, solo **compilado** o **escrito**.

## Niveles de aceptación

| Nivel | Estado |
|---|---|
| A. Estructura preparada | Hecho, salvo abrirlo en Android Studio (sin comprobar) |
| B. Host Android funcional | Probado en un Redmi Note 8 |
| C. Recompilación integrada | Probado con el disco USA soportado: el juego corre en ARM64 |
| D. Primer arranque jugable | En curso: arranca, dibuja y responde a los controles; falta jugar una partida |
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

Primer arranque en el Redmi Note 8, con la copia `unverified-variant` y Play Protect desactivado por el usuario:

- La ISO se valida e instala desde el launcher (4,3 GB). `libmain.so` carga sin instrucción ilegal.
- Vulkan 1.1 funciona con las alternativas: render passes, fences, más pipelines, DXT decodificado al subir. Las escenas 3D, una
  demo de gameplay con su interfaz y una cinemática se ven en pantalla.
- El HUD táctil responde: Start, A, B y cruceta. Se creó un perfil de prueba en el menú.
- Sesiones de varios minutos sin cierres. AAudio se abre y queda activo; **nadie ha comprobado aún que se oiga bien**.

Launcher nuevo, con el icono del usuario (`scripts/make_icon.py`): visto en vertical en un Samsung S25 Ultra (SM-S938B, Adreno 830,
Vulkan 1.3.284). Sin ver: las dos columnas en horizontal y el icono en el cajón de apps. En ese teléfono no se ha instalado el juego.

En el Samsung S25 Ultra, con el juego instalado: la ruta Vulkan 1.3 arranca y el log llegó a `warp: in level 41 TrainingLevel`.

Menú táctil nuevo en Android (`DrawTouchMenu`, `ui.cpp`): se abre con atrás, se ve, cambia de sección y se desplaza arrastrando.

Probado en el Samsung: FPS y calidad se guardan desde el launcher, y su idioma cambia (español, inglés, portugués).

Probado en el Redmi Note 8 con la versión final: 30 FPS estables a resolución 50 %, tanto en la demo como en `Mp_Frontend`, con 350 MB de
RAM. El menú del juego se lee entero. El launcher en dos columnas en horizontal. El menú táctil: secciones, idioma y volver al juego. El
botón de menú no se ve al jugar.

Medido en el Samsung:

- 60 FPS estables, con 8-10 ms por fotograma en el hilo de render, a resolución 100 %.
- 120 FPS con fotogramas generados: bajó a 37 FPS. Por eso el launcher y el menú ofrecen solo 30 y 60.
- `game_language = 5` (español) con la copia recortada lleva a la pantalla de datos ausentes a los 0,4 s del arranque: esa copia solo trae
  textos en inglés. Por eso el launcher ofrece solo los idiomas instalados.

Solo compilado, sin ejecutar: importar y usar un driver (`Drivers.kt`, `gpudriver.cpp`, `LoadVulkan`), reportar en GitHub (`Issue.kt`), el
los mandos físicos (sin mando a mano) y su atajo Guide o Start+Back, el efecto de los ajustes de calidad y
del ritmo sin espera activa (`Pace`, `TellScreenRate`). Las bibliotecas nativas se desempaquetan ahora al instalar, porque libadrenotools
abre sus hooks por ruta.

Red: Xbox Live no existe en el runtime (`xnet.cpp`). System Link sí, sin probar; en el Redmi `bind` al puerto 1001 falló con 10013, que
parece el límite de Android para puertos menores de 1024.

Con `Conker.iso`, el disco USA soportado, en el Redmi Note 8: se instala desde el launcher (881 archivos) y el usuario cargó ThroneRoom, Windmill
y TrainingLevel, con Conker en pantalla. El launcher ofrece solo los idiomas que trae la copia instalada (`GameLanguage`). Sin ver: el
texto del juego en cada idioma.

Compilar en el teléfono, sin PC. Probado en el Redmi Note 8 con los datos borrados: elegir `Conker.iso` instala la copia y compila sola en
un servicio en primer plano (881 s, 4 partes a la vez, batería a 31 °C). "Jugar" carga el `libmain.so` generado, en `files/game`, y el
juego arranca hasta su menú con los textos en español. La APK pública lleva Zig y el recompilador en la carpeta de bibliotecas nativas,
el único sitio desde el que Android deja ejecutar programas. Sin probar: otros teléfonos, una compilación cortada a medias y retomada.

El constructor para PC (`tools/package-android.cmake`, `src/apkbuilder/`) también funciona: desde la ISO a una APK firmada en 161 s, en un
PC de 12 hilos, sin instalarla en un teléfono.

Fallos abiertos:

0. En el Redmi, `vulkan: pipeline creation failed` aparece al cargar niveles: esos draws no se dibujan. El log ya dirá el motivo y el draw.

1. Con la copia recortada, empezar una partida colgaba en la pantalla de datos ausentes (`sub_001F04F0`). Con `Conker.iso` no se ha repetido, en
   una sola sesión: sin confirmar que fuese por la copia. **Falta jugar más.**
2. Los gráficos siguen por revisar con el juego en marcha: el usuario los vio mal antes de la corrección de barreras. Render al 50 %.
3. Sin comparar con PC: no se sabe qué fallos gráficos son propios de Android.

Diagnóstico en el teléfono: `files/debug_env.txt` admite una línea `XBE_...=valor` por variable (`XBE_VERBOSE=1`,
`XBE_CAPTURE_DIR=captures`). Con esta última, F3 (`adb shell input keyevent KEYCODE_F3`) guarda un fotograma en el almacenamiento de
la app. La captura fuerza el fin de cada pasada: por eso mostró el menú bien y señaló las barreras.

## Errores encontrados y su solución

| Error | Causa | Solución |
|---|---|---|
| `ld.lld: cannot open .../28\crtbegin_dynamic.o` al configurar en Windows | `file(WRITE)` de CMake escribe CRLF en Windows y Zig toma el CR como parte de la ruta | `file(CONFIGURE ... NEWLINE_STYLE UNIX)` en `cmake/zig-android.cmake` |
| `ld.lld: cmake_pch.h.o: unknown file type` | `zig cc` pasa el encabezado precompilado de SDL al enlazador | `CMAKE_DISABLE_PRECOMPILE_HEADERS` en `cmake/zig-android.cmake` |
| Un `adb install` que no termina | Diálogo de Play Protect en el teléfono | Responder en el teléfono; no se puede resolver desde el PC sin desactivar la protección |
| `.ps1` que no se analiza: `La referencia de variable no es válida` | `"$var: texto"` en PowerShell se lee como unidad de disco | `"${var}: texto"` |
| Texto del menú ausente o con colores falsos en Adreno 610 | La GPU leía el atlas de letras antes de terminar de escribirlo: barreras solo con los bits generales de memoria y render passes sin dependencias | Barreras con cada tipo de acceso por su nombre y dependencias de subpass (`kAllWrites`, `RenderPassFor`) |
| Un núcleo entero ocupado en el hilo principal | `SDL_WaitEventTimeout` gira en Android | `SDL_PollEvent` con espera de 4 ms |

## Siguiente paso

1. Reproducir el cuelgue con el registro nuevo y corregirlo.
2. Revisar los gráficos en partida y medir FPS.
3. Comprobar audio, salir y volver, guardar y cargar.
