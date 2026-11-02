# Compilar en Windows

Herramientas: Android Studio (SDK y JDK 21), Git y Python. El proyecto debe estar en una ruta sin espacios y fuera de OneDrive.

```powershell
scripts\bootstrap.ps1              # NDK 28.2.13676358, CMake 3.31.6, plataforma 36
scripts\check_environment.ps1      # qué falta y cómo instalarlo
```

## Solo el launcher, sin el juego

```powershell
scripts\build_android.ps1 -LauncherOnly
scripts\install_android.ps1
```

## Con el juego

```powershell
python scripts\extract_game.py <tu.iso> --out assets --only default.xbe
scripts\build_android.ps1          # añade -AcceptVariant si la copia no es soportada: lee antes RECOMPILATION.md
scripts\install_android.ps1
```

En el PC basta `default.xbe`. El resto de archivos se instala en el teléfono desde el launcher: copia la ISO al teléfono y elígela
ahí. Unos 5 GB.

Los builds son incrementales. En un PC de 12 hilos: recompilar el XBE tarda 7 s y compilar todo para ARM64, unos 4 min.

## Versiones

| Herramienta | Versión | Dónde se fija |
|---|---|---|
| Zig | 0.16.0, SHA-256 comprobado | `tools/toolchains.cmake` |
| NDK | 28.2.13676358 (solo sysroot y `libprobe.so`) | `app/build.gradle.kts` |
| CMake y Ninja | 3.31.6, los del SDK | `scripts/env.ps1` |
| Gradle y AGP | 9.5.1 y 9.2.1 | `src/android` |
| Android SDK | 36 | `app/build.gradle.kts` |

El encargo proponía SDK 34, Gradle 8.7 y AGP 8.6.1. Se usan las de upstream porque su proyecto Android las requiere.

## Pruebas y logs

```powershell
python tests\test_game_tools.py
scripts\collect_logs.ps1           # logcat, perfil Vulkan, motivos de salida y logs del juego, en logs\
```
