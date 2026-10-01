# GuaguaPass — Coordinación de venta de pasajes de guaguas

APK Android + servidor de sincronización en tiempo real para gestores de venta de pasajes.
**Autenticación automática y registro instantáneo de gestores con el método de ArcaneChat/chatmail**, y **sincronización del estado de asientos/guaguas por push (SSE) con fallback de polling de 1 segundo**.

## Método ArcaneChat aplicado (auto-registro de gestores)
1. `Identity.java` deriva UNA VEZ, con `SecureRandom`, un par de credenciales locales: login `gg-xxxxxx` (consonante+vocal alternadas) y contraseña base36 de 20 caracteres — igual que chatmail inventa `<aleatorio>@dominio`.
2. Al arrancar, la APK intenta `POST /api/login` con esa identidad; si devuelve 401, llama a `POST /api/auto-register` con cabecera `X-Provision-Key` (equivalente al endpoint secreto `/secret-api/new-user` de chatmail): el servidor **crea la cuenta al vuelo sin captcha ni administración** y responde token JWT de 30 días.
3. En relanzamientos basta el login normal con la identidad derivada → "autenticación automática" total. El botón **⚡ ENTRAR AUTOMÁTICO (nuevo gestor)** fuerza el flujo; también puede entrarse manualmente con usuario/contraseña propios (`/api/user` sigue disponible).
4. Cierre de la puerta: `GUAGUA_PROVISION=0 node server/server.js` desactiva el auto-registro público; `GUAGUA_PROVISION_KEY=...` cambia la clave embebida (edítala también en `Identity.PROVISION_KEY`).

## Sincronización en tiempo real (estado de asientos y guaguas)
- `GET /api/events?date=&guagua=&hora=` → **Server-Sent Events**: canal por salida; tras cada venta/cancelación el servidor **empuja** `{rev, seats}` a todos los conectados (latencia medida ~0.5 s local, muy por debajo del segundo objetivo).
- Si el stream cae (red, proxy), la app vuelve automáticamente al **polling de 1 s** sobre `GET /api/state` (mismo payload JSON).
- Conflicto de asiento: el segundo en reservar recibe HTTP 409 ("ya vendido por X") y todos los suscriptores ven el cambio al instante.

## Qué hace cada gestor con la app
1. **Login** automático (identidad derivada estilo arcanechat) o manual; el token JWT se guarda localmente y hay autologin silencioso al relanzar.
2. Elige **guagua/ruta**, **fecha del día** y **hora de salida**.
3. Ve la **rejilla física de asientos** de la guagua (4×12 = 48, numerados por columnas, fila del conductor marcada). Gris=libre, rojo=vendido por otro gestor, verde=tu venta.
4. Toca un asiento libre → formulario (pasajero, teléfono, destino, precio) → **CONFIRMAR RESERVA**.
5. Estado "● Conectado (push tiempo real)" en pantalla; cambios de otros gestores aparecen al instante.

## Componentes
| Ruta | Descripción |
|---|---|
| `src/com/guaguapass/Identity.java` | Credenciales derivadas estilo chatmail (SecureRandom + seed persistente) |
| `src/com/guaguapass/LoginActivity.java` | Auto-login arcanechat (login→fallback auto-register) + login manual |
| `src/com/guaguapass/MainActivity.java` | Rejilla en vivo, stream SSE con fallback polling 1 s, lista de ventas |
| `src/com/guaguapass/SaleActivity.java` | Formulario de reserva |
| `src/com/guaguapass/Api.java`, `Session.java` | HTTP client (incluye parser SSE) y sesión persistida |
| `server/server.js` | Node.js sin dependencias: auto-register protegido por `X-Provision-Key`, canales SSE, broadcast en ventas/cancelaciones/conflictos, scrypt+JWT, db.json |
| `build.sh` | Compila la APK con aapt2/javac/d8/apksigner (sin Gradle) |

## Uso
```bash
# 1. Servidor (PC o VPS accesible desde los teléfonos)
GUAGUA_DOMAIN=mi-servidor GUAGUA_PROVISION_KEY=clave-cambiar node server/server.js 3000
# opcional: crear gestores manualmente igualmente funcionan:
curl -X POST http://IP:3000/api/user -d '{"username":"maria","password":"clave","name":"María"}'

# 2. Instalar out/GuaguaPass.apk en cada teléfono: solo escriben la URL del servidor
#    y pulsar ⚡ ENTRAR AUTOMÁTICO — cada teléfono queda con su propio gestor gg-xxxxxx.
```

## Configurar flota
Edita `CONFIG` en `server/server.js`: guaguas, rutas, horarios y dimensiones de asientos (`dims.cols × dims.rows`). La app lee todo de `GET /api/config` (que además publica `domain` y `provision_open`).

## Build de la APK
```bash
ANDROID_HOME=/opt/android-sdk ./build.sh   # -> out/GuaguaPass.apk (firmada, debug keystore)
```

## Notas de seguridad
- Cambia la contraseña del keystore (`out/guagua.keystore`) y `Identity.PROVISION_KEY`/`GUAGUA_PROVISION_KEY` para producción.
- El auto-registro abierto permite que cualquiera con la URL+clave cree gestores; usa `GUAGUA_PROVISION=0` y entrega QR/manual de credenciales cuando quieras controlarlo (el mismo patrón: chatmail cierra su secret-api con rate-limit y PoW).
- En producción sirve el servidor tras HTTPS/TLS (nginx/caddy soporta SSE con `proxy_buffering off`) y usa `https://` en la URL del login.
