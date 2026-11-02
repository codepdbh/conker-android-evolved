# Adaptaciones para Android

Las de upstream están en su rama `android`: NEON en lugar de SSE, cuenta de tiempo ARM, páginas de 16 KB, `memfd` por syscall,
sin superficie en segundo plano, DXT sin BC. Las de este proyecto:

| Cambio | Archivos | Motivo |
|---|---|---|
| Objetivo `-mcpu=generic` (ARMv8.0) | `cmake/zig-android.cmake` | Cortex-A73 y A53 no tienen ARMv8.2 ni LSE |
| Build en Windows | `cmake/zig-android.cmake`, `.gitattributes` | Ver [PROGRESS.md](PROGRESS.md) |
| Vulkan 1.1 | `src/runtime/gpu/vulkan.cpp`, `draw.h` | Ver [GRAPHICS.md](GRAPHICS.md) |
| El juego en el proceso `:game` | `AndroidManifest.xml` | Un crash no cierra el launcher |
| Launcher sin depender de `libmain.so` | `SetupActivity.kt`, `Disc.kt`, `probe.cpp` | Debe poder enviar logs aunque el juego no cargue |
| Paquete propio | `build.gradle.kts` | Se instala junto a la app de upstream, con sus propios datos |

## Dónde se guarda cada cosa

| Qué | Dónde |
|---|---|
| Archivos del juego | `files/assets` (interno) |
| Ajustes, guardados, `touch_layout.txt` | `files/` (interno) |
| Logs del juego | `Android/data/com.conker.androidevolved/files/logs` |
| Informes | `cache/reports`, servidos solo por `ReportProvider` con permiso temporal |

Borrar los datos de la app elimina también los guardados.

## Permisos

`VIBRATE`, `INTERNET` y `ACCESS_NETWORK_STATE`, estos dos para el System Link. Sin acceso a todos los archivos: la copia se elige
con el selector de Android.

## Informe de logs

Contiene `device.txt`, `probe.txt`, `state.txt`, `exit-reasons.txt`, `logcat.txt` (solo líneas de esta app), los logs del juego,
los tombstones (Android 12 o superior) y `settings.toml`. No contiene archivos del juego, partidas guardadas ni datos de otras
apps. Solo sale del teléfono si el jugador lo envía.
