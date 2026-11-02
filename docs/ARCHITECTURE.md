# Arquitectura

```
PC:       default.xbe -> xbe_recomp -> C++ -> zig c++ (AArch64) ──┐
                                                                  v
Teléfono: launcher (proceso principal)              libmain.so (proceso :game)
          SetupActivity   elegir e instalar copia     juego recompilado
          TouchEditor     editar el HUD               runtime: kernel, XDK, Vulkan, audio, SDL3
          Report          informe de logs                 ^
          libprobe.so     CPU y GPU                       │
                 └──────── GameActivity ──────────────────┘
```

Sin emulador: la APK ejecuta código nativo ARM64. El código x86 se traduce en el PC, antes de empaquetar.

| Carpeta | Contenido | Origen |
|---|---|---|
| `src/runtime/` | Kernel, XDK, renderer, audio, host | Upstream, con cambios |
| `src/conker/` | Hooks y parches del juego | Upstream |
| `src/recomp/`, `src/symbols/` | Recompilador x86 a C++ | Upstream |
| `src/android/` | App: launcher, editor, sonda | Upstream, muy ampliado |
| `src/setup/`, `src/apkbuilder/` | Instalador y constructor de APK para jugadores | Upstream, sin cambios |
| `scripts/` | Build, validación y logs para Windows | Este proyecto |
| `tests/` | Pruebas con datos sintéticos | Este proyecto |
| `assets/`, `generated/`, `out/`, `toolchain/`, `logs/` | Fuera de git | Local |

La estructura de carpetas del encargo era una propuesta. Se conserva la de upstream para poder seguir incorporando sus cambios.

## Pendiente de ajustar

`tools/package-android.cmake` y `src/apkbuilder/` siguen siendo los de upstream: no incluyen `libprobe.so` en sus cuentas ni se han
probado con estos cambios. El camino probado de build es `scripts/build_android.ps1`.

## Licencia

GPL-3.0 only, con las partes de Xbox XBE Recomp bajo MIT (`LICENSE`, `THIRD_PARTY.txt`). El juego no forma parte de ella.
