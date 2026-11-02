# Controles

**Estado: compilado, nunca ejecutado.** El HUD del juego lo dibuja `src/runtime/app/touch.cpp`, de upstream, ampliado aquí.

## Controles

Dos sticks que aparecen donde cae el pulgar (el izquierdo en la zona inferior izquierda, el derecho en el resto), A, B, X, Y,
White, Black, LT, RT, D-Pad, Back, Start y un botón de menú. Varios dedos a la vez, hasta 10.

## Editor

Launcher > **Editar y probar controles**, sin necesidad del juego:

- Arrastrar para mover; pellizcar o usar − y + para cambiar el tamaño.
- Opacidad, ocultar con mando y vibración.
- **Probar**: muestra qué controles se pulsan a la vez.
- **Restablecer** y **Guardar**.

## `touch_layout.txt`

En los archivos internos de la app. Lo escribe el editor y `touch.cpp` lo lee al arrancar el juego.

```
a 0.8500 0.8000 1.200      control, x e y como fracción de la pantalla, factor de tamaño
opacity 0.70
hide_with_pad 1
vibrate 1
```

Un control que no aparezca se queda en su sitio por defecto. **Las posiciones por defecto están duplicadas** en `Layout()`
(`touch.cpp`) y `defaults()` (`TouchEditorActivity.kt`): al cambiar una hay que cambiar la otra.

## Pendiente

- Zonas seguras en el HUD del juego: solo el editor las respeta al mover un control.
- Perfiles por contexto (exploración, combate, vehículos). Primero hay que verificar los controles reales en el juego.
