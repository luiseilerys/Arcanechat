# GuaguaPass — Coordinación de venta de pasajes de guaguas

APK Android + servidor de sincronización en tiempo real para gestores de venta de pasajes.

## Qué hace cada gestor con la app
1. **Login** con su usuario y contraseña (autologin al relanzar; el token JWT se guarda localmente).
2. Elige **guagua/ruta**, **fecha del día** y **hora de salida**.
3. Ve la **rejilla física de asientos** de la guagua (4×12 = 48, numerados por columnas: 1-12 izquierda delantera→trasera, etc., con fila del conductor marcada). Colores: gris=libre, rojo=vendido por otro gestor, verde=tu venta.
4. Toca un asiento libre → formulario (pasajero, teléfono, destino, precio) → **CONFIRMAR RESERVA**.
5. **Sincronización cada 1 segundo**: la app hace polling de `GET /api/state`; cuando otro gestor vende, el asiento pasa a rojo en ≤1 s. Si dos intentan el mismo asiento casi a la vez, el servidor acepta solo uno y el otro recibe HTTP 409 ("ya vendido por X").

## Componentes
| Ruta | Descripción |
|---|---|
| `src/com/guaguapass/LoginActivity.java` | Login + autologin silencioso |
| `src/com/guaguapass/MainActivity.java` | Rejilla en vivo, polling 1 s, lista de ventas |
| `src/com/guaguapass/SaleActivity.java` | Formulario de reserva |
| `src/com/guaguapass/Api.java`, `Session.java` | HTTP client y sesión persistida |
| `server/server.js` | Servidor Node.js sin dependencias (auth scrypt+JWT, estado, conflicto 409, db.json) |
| `build.sh` | Compila la APK con aapt2/javac/d8/apksigner (sin Gradle) |

## Uso
```bash
# 1. Servidor (en un PC o VPS accesible desde los teléfonos)
node server/server.js 3000
# crear gestores:
curl -X POST http://IP:3000/api/user -d '{"username":"maria","password":"clave","name":"María"}'

# 2. Instalar out/GuaguaPass.apk en cada teléfono y poner la URL del servidor en el login
```

## Configurar flota
Edita `CONFIG` en `server/server.js`: guaguas, rutas, horarios y dimensiones de asientos (`dims.cols × dims.rows`). La app lee todo de `GET /api/config`.

## Build de la APK
```bash
ANDROID_HOME=/opt/android-sdk ./build.sh   # -> out/GuaguaPass.apk (firmada, debug keystore)
```

## Notas de seguridad
- Cambia la contraseña del keystore (`out/guagua.keystore`) para producción.
- En producción sirve el servidor tras HTTPS/TLS (nginx/caddy) y usa `https://` en la URL del login.
- Para escala mayor, sustituir el polling por WebSocket/SSE es trivial (el campo `rev` ya permite detección de cambios).
