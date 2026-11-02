# Gráficos

El renderer de upstream (`src/runtime/gpu/vulkan.cpp`) traduce los comandos de la NV2A a Vulkan. Este proyecto no lo reescribe:
baja el requisito de 1.3 a 1.1 eligiendo en tiempo de ejecución lo que la GPU declara. El log lo resume en la línea `Vulkan:`.

**Estado: compilado, nunca ejecutado.** La ruta de Vulkan 1.3 también cambió de estructuras, así que debe volver a probarse en PC.

| Función de 1.2/1.3 | Si la GPU no la tiene | Coste |
|---|---|---|
| Versión de API | Instancia y dispositivo en la versión común, mínimo 1.1 | Ninguno |
| SPIR-V 1.6 | SPIR-V 1.3 | Ninguno previsto |
| Dynamic rendering | Un `VkRenderPass` por formato y muestras, y framebuffers que viven con el command buffer | Crear framebuffers por frame |
| Timeline semaphores | Una `VkFence` por envío, con cola en orden | Mínimo |
| Host query reset | `vkCmdResetQueryPool` por lotes de 64, fuera del render pass | Corta un pass cada 64 consultas |
| Extended dynamic state | Topología y strides dentro de `PsoKey` | Más pipelines: más tirones la primera vez |
| Push descriptors | Sets de pools por frame, reiniciados cuando la GPU termina | Más CPU por draw |
| Texturas BC | DXT decodificado a BGRA al subir (upstream) | Más RAM y más carga |
| Geometry shaders | Las listas de puntos no se dibujan (upstream) | Falta visual por medir |

## Valores por defecto en Android

30 FPS y render al 50 % de la altura de salida. Se cambian en el menú del juego. Sin medir aún si son los adecuados.
