# Comparación de repositorios y obstáculos

## Conclusión

La prioridad del encargo era adaptar el runtime de Conker a Android tomando MostWanted-Android como referencia de arquitectura.
Esa adaptación ya existe: la rama `android` de live-and-recompiled hace lo que íbamos a construir y usa el motor de Conker. Por
eso partimos de ella y no de un host de NFS. El trabajo de este repositorio es lo que le falta: teléfonos sin Vulkan 1.3, build
en Windows, launcher con diagnóstico y HUD editable.

## Versiones analizadas

| Repositorio | Commit | Uso |
|---|---|---|
| Mabec-games/live-and-recompiled `android` | `25e614d` | **Base de este proyecto** |
| Mabec-games/live-and-recompiled `launcher` | `e038167` | La rama que usa Conker Recomped & Reloaded |
| Mabec-games y giacomozama/live-and-recompiled `main` | `ee9ad4e` | Mismo commit en ambos |
| Mabec-games/Conker-Recomped-and-Reloaded | `698a6ca` | Launcher Qt de escritorio; no se porta |
| RanggaCasper/MostWanted-Android | `87b951e` | Referencia del HUD |
| RanggaCasper/Black-Android | `8e9c6fc` | Referencia |
| RanggaCasper/ShaolinMonks-Android | `2e346bc` | Referencia |
| TGP482/XboxXBERecomp y XboxXBE-Recomp | `f7b13fd` | **Mismo commit en ambas URL**: en esta fecha son intercambiables |

Alcance: de los ports de RanggaCasper se leyó el README, la estructura y la configuración de Gradle, no su código línea a línea.
Las celdas de esos ports se limitan a eso.

## Conker frente a MostWanted-Android

| Aspecto | Este proyecto | MostWanted-Android |
|---|---|---|
| Juego y motor | Conker, con hooks y parches propios | NFS Most Wanted; no reutilizable para Conker |
| Compilador | Zig 0.16.0 fijado, contra el sysroot del NDK | NDK 28.2.13676358 |
| Vulkan mínimo | 1.1 con alternativas (sin probar) | 1.1 según su manifest |
| Android mínimo | API 28 | API 29 |
| Almacenamiento | Storage Access Framework; el juego se copia a la memoria de la app | Acceso a todos los archivos, carpeta fija |
| Licencia | GPL-3.0 only; las partes de XBE Recomp, MIT | MIT |
| HUD | Editable (posición, tamaño, opacidad) | Editable; su disposición está pensada para conducción |
| Logs | Informe que el jugador envía desde el launcher | Carpeta `logs` en el almacenamiento |

## Obstáculos reales

1. **La copia del juego.** Su SHA-1 no es de una versión soportada ([RECOMPILATION.md](RECOMPILATION.md)).
2. **Vulkan.** Upstream exige 1.3 y muchos teléfonos económicos tienen 1.1. Hay alternativas escritas, sin probar en un teléfono.
3. **Rendimiento.** Sin medir. Un SD665 es muy inferior a los teléfonos que este juego podría exigir; 30 FPS no está garantizado.
4. **Memoria.** Sin medir. El renderer reserva 192 MB para subidas (3 × 64 MB), y decodificar DXT en la GPU sin BC gasta más
   que en un PC. El teléfono de prueba tiene 3,6 GB.
5. **GPU sin geometry shaders.** Las listas de puntos no se dibujan en ellas. Falta medir qué partes del juego afectan.
6. **Drivers.** El Adreno 610 de prueba usa un driver de 2020. Se esperan errores propios de cada fabricante.
