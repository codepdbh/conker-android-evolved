# Recompilación

```
default.xbe (x86) -> xbe_recomp + src/conker/game.toml -> generated/game/*.cpp -> zig c++ (AArch64) -> libmain.so
```

`generated/` y `assets/` contienen material derivado del juego. Git los ignora y **no se pueden publicar**. Tampoco una APK
con `libmain.so`.

## Identificar la copia

```powershell
python scripts\validate_game.py <iso, carpeta o default.xbe>
```

| Veredicto | Significado | Qué hace `recompile_xbe.ps1` |
|---|---|---|
| `supported` | SHA-1 exacto de una versión soportada | Recompila |
| `unverified-variant` | Otro SHA-1, sin contradicciones aparentes | Compara; solo sigue con `-AcceptVariant` |
| `different-build`, `altered`, `wrong-game` | Otra compilación, secciones alteradas u otro juego | No debe recompilarse |

Solo el SHA-1 exacto cuenta como soportado. El resto es evidencia, no equivalencia.

## La copia actual: `unverified-variant`

SHA-1 de `default.xbe`: `bef166b2d2f72fef6bc051fd7bc36caadada0b54`. Región `0x7`, medios `0x400001FF`, versión 2, enlazado el
2005-04-28.

| Comprobación | Resultado |
|---|---|
| Title ID y punto de entrada | Coinciden con `game.toml` |
| Digest de las 27 secciones | Todos válidos |
| Funciones localizadas por `xbe_symbols` | 122, todas en la dirección de la configuración |
| Afirmaciones sobre bytes concretos en `game.toml` | Las 4 comprobadas se cumplen |
| 28 hooks | Sus nombres aparecen en el código generado |
| Restaurar región, medios, ratings e init flags para obtener `2232a1b3…` | **No se obtiene** en 24.300 combinaciones |

Por tanto no está demostrado que sea idéntica a una versión soportada. Las funciones comparadas son de bibliotecas XDK: el código
propio del juego solo se ha contrastado en los puntos anteriores, y una diferencia ahí no se detectaría. No se ha modificado ni la
copia ni ninguna firma. Si el juego se comporta de forma extraña, descartar esta causa antes de culpar al port: se resolvería con
el disco USA o la versión de Microsoft Store.

La versión digital también se documenta en upstream con el SHA-1 `68a37f0d…`: mismo código y datos, otra cabecera.

## Cómo acepta la app una copia

`libmain.so` guarda el SHA-1 del XBE con el que se compiló. `build_android.ps1` escribe ese mismo valor en
`accepted_xbe.txt`, y el launcher rechaza cualquier otro que no sea una versión soportada.
