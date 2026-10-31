# Mapa para el chat — mod de ejemplo

Mod de tipo **WebXDC**: no cambia la apariencia de la app, sino que distribuye una app WebXDC
(`apps/mapa.xdc`, la misma que trae ArcaneChat en `src/main/assets/webxdc/maps.xdc`).

Al activar la tarjeta del mod en **Ajustes → Avanzado → Mods de la comunidad** puedes pulsar
**Compartir app** y elegir el chat: ArcaneChat enviará el `.xdc` como adjunto y el resto del grupo
podrá abrirlo con un toque.

## Empaquetar

```bash
./scripts/empaquetar-mod.sh mods/ejemplo-app-mapa
```
