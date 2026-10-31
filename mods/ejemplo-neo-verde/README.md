# Neo Verde — mod de ejemplo

Mod mínimo que demuestra dos capacidades:

- `theme: "green"` → activa el tema verde integrado de ArcaneChat (también existen `light`,
  `dark`, `blue`, `gray`, `pink`, `purple`, `red` y `system`).
- `background: "background.png"` → imagen de fondo de chat (720×1280, generada con un degradado).
- `notificationSound: "sounds/notify.wav"` → tono de notificación (dos notas suaves, 0,45 s;
  requiere Android 10+).

## Empaquetar

```bash
./scripts/empaquetar-mod.sh mods/ejemplo-neo-verde
# -> dist/org.arcanechat.comunidad.neo-verde-1.0.0.acmod
```

Instálalo desde **Ajustes → Avanzado → Mods de la comunidad → Instalar mod**.
