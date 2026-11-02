# Compatibilidad

No se ha ejecutado el juego en ningún dispositivo. Esta página dice qué declara el hardware y qué exige el código, no qué funciona.

## Requisitos

| Requisito | Upstream | Este proyecto |
|---|---|---|
| CPU | ARMv8.2 | ARMv8.0 con NEON |
| Vulkan | 1.3 | 1.1 |
| Android | 9 (API 28) | 9 (API 28) |
| Almacenamiento libre | unos 6 GB | unos 5 GB |

## Dispositivos

| Dispositivo | SoC y GPU | Vulkan | RAM | Resultado |
|---|---|---|---|---|
| Redmi Note 8 (`ginkgo`), ROM Android 17 | SD665, Adreno 610, driver 512 de 09/2020 | 1.1.128 | 3,6 GB | Datos leídos; juego sin probar |

### Redmi Note 8: lo que declara

| Función | ¿Tiene? | Qué hace el renderer |
|---|---|---|
| Atómicos LSE | No | Atómicos ARMv8.0 |
| Dynamic rendering | No | Render passes |
| Timeline semaphores | No | Una fence por envío |
| Extended dynamic state | No | Un pipeline por topología y stride |
| Depth stencil resolve, custom border color | No | Sin dynamic rendering no hace falta el primero; el segundo ya era opcional |
| Texturas BC | No | Decodifica DXT al subir la textura |
| Push descriptors | Sí | Nativo |
| Host query reset | Sí | Nativo |
| Geometry y tessellation shaders | Sí | Nativo |

## MediaTek y Exynos

Sin datos de ningún dispositivo: no hay ninguno disponible para probar. El código no depende del fabricante y cada alternativa
se elige por lo que declara la GPU. Riesgos conocidos:

- **Mali (Helio, Dimensity, Exynos antiguos):** upstream indica que no tiene geometry shaders, así que no se dibujarían las
  listas de puntos. Comprobarlo con informes reales.
- **Helio con Cortex-A53:** CPU muy lenta para el juego; arrancar no implica que sea jugable.
- **Xclipse (Exynos recientes):** sin datos.

El informe del launcher incluye el perfil completo de CPU y GPU, de modo que cada teléfono que lo envíe puede añadirse aquí.
