# Hoja de ruta

Cada fase termina con una prueba en un teléfono, no con una compilación.

| Fase | Criterio de aceptación | Estado |
|---|---|---|
| Launcher | Se instala, abre, muestra la compatibilidad y envía un informe | Compilado |
| HUD en el editor | Mover, redimensionar, guardar, probar varios dedos | Compilado |
| Instalar la copia | La ISO se valida y se instala desde el teléfono | Compilado |
| Carga | `libmain.so` carga sin instrucción ilegal | Sin probar |
| Vulkan 1.1 | Dispositivo creado, pipelines y shaders sin errores, primer fotograma | Sin probar |
| Menú principal | Imagen correcta, audio, controles | Sin probar |
| Gameplay | Mover a Conker, cámara, cambio de escena | Sin probar |
| Vida útil | Salir y volver, rotación, guardar y cargar | Sin probar |
| Vulkan 1.3 en PC | La ruta de siempre sigue funcionando | Sin probar |
| Cinemáticas | Reproducción y sincronía | Sin probar |
| Rendimiento | Medir FPS y memoria; ajustar valores por defecto | Sin probar |
| Otras GPU | Informes de Mali y Xclipse | Sin dispositivos |
| Listas de puntos sin geometry shaders | Dibujarlas en Mali, si el juego las necesita | Sin empezar |
| Perfiles de HUD por contexto | Sin perder ninguna acción | Sin empezar |
| Multijugador | Local y en red | Sin empezar |
