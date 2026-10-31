# Mods de la comunidad (`.acmod`)

ArcaneChat acepta **mods declarativos** hechos por la comunidad: paquetes que cambian el aspecto
o añaden apps WebXDC, sin ejecutar código de terceros dentro de la app.

- Pantalla en la app: **Ajustes → Avanzado → Mods de la comunidad**.
- Formato: un ZIP (`*.acmod`) con `manifest.json` en la raíz. Contenido permitido: imágenes,
  audio, apps `.xdc`, JSON y texto.
- Se puede activar **un mod a la vez** (funciona como un "perfil"). Al activarlo se guardan tus
  ajustes actuales y se restauran al desactivarlo.
- Validación en el dispositivo antes de instalar (ver [Reglas de seguridad](#reglas-de-seguridad)).

---

## 1. Estructura del paquete

```
mi-mod-1.0.0.acmod
├── manifest.json          (obligatorio, en la raíz)
├── background.png         (opcional, ≤ 8 MB)
├── sounds/notify.ogg      (opcional, ≤ 3 MB)
├── apps/mapa.xdc          (opcional, ≤ 16 MB cada una)
├── icon.png               (opcional; se muestra como icono en el futuro)
└── README.md              (opcional, informativo)
```

Todo lo que no declare el manifiesto se instala pero no se usa (útil para capturas o documentación).

## 2. `manifest.json`

```json
{
  "format": 1,
  "id": "org.ejemplo.neo-verde",
  "name": "Neo Verde",
  "version": "1.0.0",
  "author": "Tu nombre o nick",
  "description": "Tema verde con fondo degradado y sonido propio.",
  "theme": "green",
  "background": "background.png",
  "notificationSound": "sounds/notify.ogg",
  "webxdc": ["apps/mapa.xdc"],
  "homepage": "https://ejemplo.org/mi-mod"
}
```

| Campo | Obligatorio | Reglas |
|---|---|---|
| `format` | no (`1`) | Único valor admitido hoy: `1` |
| `id` | **sí** | 3–64 caracteres `[a-z0-9._-]`, empieza por letra o número |
| `name` | no | ≤ 80 caracteres; si falta se usa el `id` |
| `version` | no | `1`, `1.0`, `1.0.0` o `1.0.0-beta1` |
| `author` | no | ≤ 80 caracteres |
| `description` | no | ≤ 500 caracteres |
| `theme` | no | `light`, `dark`, `blue`, `gray`, `green`, `pink`, `purple`, `red`, `system` |
| `background` | no | ruta relativa a una imagen del paquete |
| `notificationSound` | no | ruta relativa a un audio del paquete (`.ogg`/`.wav`; requiere Android 10+) |
| `webxdc` | no | lista de rutas a archivos `.xdc` |
| `homepage` | no | debe empezar por `https://` |

Debe declararse **al menos una capacidad** (`theme`, `background`, `notificationSound` o `webxdc`);
si no, el paquete se rechaza por no hacer nada.

### Qué hace cada capacidad al activar el mod

| Capacidad | Efecto real en la app |
|---|---|
| `theme` | Cambia el tema de color global (el mismo ajuste que *Apariencia → Tema*). |
| `background` | Copia la imagen y la usa como fondo de chat de la cuenta actual. |
| `notificationSound` | Se publica en `MediaStore` (carpeta `Notifications/ArcaneChatMods`) y se usa como tono de notificación. En Android 9 o anterior se omite el sonido, el resto del mod se aplica. |
| `webxdc` | No cambia la apariencia: habilita **Compartir app** para enviar el `.xdc` a un chat. |

Al desactivar, la app restaura exactamente el tema, el fondo y el tono que tenías antes.

## 3. Crear y probar un mod

```bash
# 1) crea una carpeta con manifest.json y tus archivos
mkdir -p mods/mi-mod && $EDITOR mods/mi-mod/manifest.json

# 2) empaquétalo (genera dist/<id>-<version>.acmod y lo valida)
./scripts/empaquetar-mod.sh mods/mi-mod

# 3) validación suelta, con detalle de archivos
python3 scripts/validar-mod.py dist/mi-mod-1.0.0.acmod -v
```

En el teléfono: **Ajustes → Avanzado → Mods de la comunidad → Instalar mod** y elige el `.acmod`
(se abre el selector de documentos; sirve cualquier gestor de archivos o descarga del navegador).

Ejemplos completos en [`mods/`](../../mods):

- `mods/ejemplo-neo-verde`: tema + fondo.
- `mods/ejemplo-app-mapa`: app WebXDC.

## 4. Reglas de seguridad

Un mod **nunca** puede ejecutar código nativo, DEX, JAR, APK, JavaScript ni scripts de shell.
`ModInstaller` valida lo siguiente **antes** de instalar y de nuevo al extraer:

1. Tamaño del archivo ≤ 32 MB; suma descomprimida ≤ 64 MB; ≤ 256 entradas (anti *zip bomb*).
2. Rutas: sin `/` inicial, sin `..`, sin `\`, sin caracteres de control ni `: * ? " < > |`
   (anti *zip-slip*); además se comprueba que la ruta canónica quede dentro del directorio del mod.
3. Extensiones permitidas: `json md txt png jpg jpeg webp gif ogg mp3 m4a opus wav xdc`.
   Cualquier otra cosa (`.apk`, `.dex`, `.jar`, `.so`, `.sh`, `.js`, `.html`, `.class`, …) → rechazo.
4. Topes por archivo: fondo 8 MB, sonido 3 MB, cada `.xdc` 16 MB, manifiesto 64 KB.
5. Todos los archivos que declara el manifiesto deben existir.
6. El paquete se extrae en un directorio temporal y solo se mueve a `files/mods/<id>` si todo es
   válido; si algo falla, se borra y no queda nada a medias.

Los mods activos leen archivos de `files/mods/<id>/`, inaccesible por otras apps. La única excepción
es el `.xdc`, que se comparte con el chat a través del `FileProvider` interno y solo cuando pulsas
**Compartir app**.

El sonido de notificación sí necesita ser legible por el sistema (lo reproduce el servicio de
notificaciones), así que se copia a `MediaStore` como un audio normal y se borra al desactivar el
mod. Eso requiere Android 10 o superior; en versiones anteriores el mod se activa sin cambiar el
tono (la interfaz lo indica en **Detalles**).

## 5. Publicar un mod

- Sube el `.acmod` a una release de GitHub, a tu web o a un canal de ArcaneChat.
- Incluye el `id`, la versión y la lista de capacidades en la descripción: la app muestra esos datos
  en **Detalles** antes de activar.
- Firma/hash recomendados (para una futura verificación automática):

  ```bash
  sha256sum dist/mi-mod-1.0.0.acmod
  ```

> **Pendiente (roadmap)**: firma de paquetes con clave del autor, repositorios/índices de mods con
> `sha256` y actualizaciones automáticas, e icono y capturas en la tarjeta del mod.

## 6. Preguntas frecuentes

**¿Puedo tener dos mods activos (uno de tema y otro de apps)?**
No: el modelo actual es "un perfil activo". Puedes empaquetar varias capacidades en un solo mod.

**¿Se pierde mi configuración al activar un mod?**
No. Se guarda una copia (tema, fondo y tono) y se restaura al desactivar o desinstalar.

**¿Los mods se sincronizan entre dispositivos?**
No. Son locales a cada instalación.

**¿Dónde se guardan?**
`/data/data/<paquete>/files/mods/<id>/` (inaccesible sin root). El tono, si lo hay, aparece como
audio en `Notifications/ArcaneChatMods` mientras el mod está activo.

**¿Por qué el sonido no funciona en Android 9?**
Porque el `content://` de la app no es legible por el servicio de notificaciones y en esas versiones
no se puede escribir en `MediaStore` sin permisos de almacenamiento. El tema, el fondo y las apps
WebXDC funcionan igual.
