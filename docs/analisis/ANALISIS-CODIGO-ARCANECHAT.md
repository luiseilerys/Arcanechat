# Análisis profundo de código — ArcaneChat (Android) + GuaguaPass

**Repositorio:** `luiseilerys/Arcanechat`
**Rama analizada:** `arena/ddc22f4d-arcanechat` (base `fed28f8`, único commit: squash de todo el proyecto)
**Fecha del análisis:** 2026-10-31
**Alcance:** los 446 archivos Java de producción (454 contando tests instrumentados y variantes de flavor) + `jni/dc_wrapper.c` + `build.gradle` + manifiestos + workflows de CI + `guaguapass/server/server.js` y `guaguapass/build.sh`
**Naturaleza:** revisión estática (lectura de código), sin ejecución de la app ni del servidor

> Complementos de este informe:
> - `docs/analisis/inventario-archivos.csv` — los 446 archivos con líneas, paquete y capa.
> - `docs/analisis/hallazgos.csv` — listado tabular de todos los hallazgos con severidad y evidencia.

---

## 1. Resumen ejecutivo

### 1.1 Qué es este repositorio

Dos productos en un mismo árbol:

| Producto | Qué es | Tamaño | Estado técnico |
|---|---|---|---|
| **ArcaneChat** (`src/main/java`, 439 archivos, ~71.100 LOC) | Cliente Android de Delta Chat (fork de `deltachat-android`) con extras: WebXDC ampliado, llamadas WebRTC, "relays", onboarding instantáneo chatmail | 439 archivos Java, 427 XML, 417 PNG | Código heredado maduro + parches propios; calidad desigual |
| **GuaguaPass** (`guaguapass/`, 7 archivos Java + 1 servidor Node, ~2.250 LOC) | App de venta de pasajes de guaguas + servidor de sincronización SSE | 7 Java, 479 líneas de `server.js` | Prototipo funcional con **fallos de seguridad graves** y artefactos de compilación versionados |

El núcleo de mensajería (Rust, `jni/deltachat-core-rust`) **no está en el checkout** (submódulo vacío), por lo que el análisis del core criptográfico/protocolo queda fuera: se analiza el puente JNI (`jni/dc_wrapper.c`), los bindings (`com/b44t/messenger/*`) y todo el lado Java.

### 1.2 Los ocho hallazgos que más importan

| # | Hallazgo | Severidad | Evidencia |
|---|---|---|---|
| 1 | **Keystore de firma de GuaguaPass + contraseña (`guagua123`) versionados en Git**, junto a 4 APK de salida | 🔴 Crítico | `guaguapass/out/guagua.keystore`, `guaguapass/build.sh:39-46`, `guaguapass/out/*.apk` |
| 2 | **Auto-actualización de GuaguaPass** que descarga un APK y lo lanza al instalador sin verificación criptográfica real; combinada con (1) permite publicar una "actualización" firmada con la clave pública del repo | 🔴 Crítico | `guaguapass/src/com/guaguapass/Updater.java:55-75`, `AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`), `build.sh` |
| 3 | **Clave de aprovisionamiento embebida** (`guagua-provision-key`) y auto-registro abierto por defecto → cualquiera que extraiga el APK crea cuentas; `/api/user` sin autenticación | 🟠 Alto | `Identity.java:43`, `server.js:34-35,105-108,353-358` |
| 4 | **Servidor GuaguaPass sin TLS, sin rate-limit, con JWT sin revocación y comparación de firma no constante**, que además imprime la clave de provisión en el log | 🟠 Alto | `server.js:154-170,360-369,475-479` |
| 5 | **Fuga de datos personales entre gestores**: `/api/state` devuelve nombre, teléfono, destino y precio de *todas* las ventas a *cualquier* usuario autenticado | 🟠 Alto | `server.js:202-213` (`statePayload`) |
| 6 | **Manejador de crashes de ArcaneChat envía el logcat completo + stack trace a un correo fijo** (`adb@merlinux.eu`) sin redacción ni consentimiento explícito; además invoca `finalize()` | 🟠 Alto (privacidad) | `ApplicationContext.java:160-186` |
| 7 | **Deadlock de arranque**: si la inicialización lanza un `Error` (no `Exception`), `isInitialized` nunca se marca y todo hilo que llame a `getDcAccounts()`/`getContext()` se bloquea para siempre (ANR) | 🟠 Alto (robustez) | `ApplicationContext.java:78-84, 296-301` |
| 8 | **Puente JSON-RPC sin timeout ni manejo de fallos del hilo lector**: si el hilo muere, todas las llamadas RPC quedan colgadas; si la respuesta no es JSON, bucle apretado (CPU al 100 %) | 🟠 Alto (robustez) | `chat/delta/rpc/BaseRpcTransport.java:47-95` |

### 1.3 Veredicto por área

| Área | Nota | Comentario |
|---|---|---|
| Arquitectura general (ArcaneChat) | 7/10 | Capas claras (UI → `DcHelper` → JNI → core), pero con "god classes" y estado estático global |
| Seguridad de la app principal | 6,5/10 | Buenas bases heredadas (Keystore para secreto de BD, `allowBackup=false`, WebView con CSP y proxy nulo); debilidades en logging, handlers de crash y validación de origen en WebXDC |
| Calidad de código principal | 6/10 | Formato consistente (Spotless + google-java-format), pero 137 `catch` genéricos, 69 `printStackTrace`, 655 logs, cero tests unitarios |
| CI/CD principal | 6/10 | 3 de 7 workflows correctos; `build-apk.yml` está roto |
| **GuaguaPass (app)** | 3/10 | Funciona como demo; sin lifecycle, sin i18n, JSON parseado a mano, actualización insegura |
| **GuaguaPass (servidor)** | 3,5/10 | Sin TLS, sin rate-limit, sin control de acceso a datos, persistencia no atómica; buena idea de SSE |
| Higiene del repositorio | 3/10 | `.gitignore` vacío; se versionan `build/`, `.gradle/`, APKs, `.class`, `.dex`, keystore |

---

## 2. Método y límites

**Qué se hizo**

1. Inventario completo del árbol (1.502 archivos), agrupación por extensión y por paquete, conteo de LOC.
2. Lectura íntegra de los archivos propios/de mayor riesgo: `guaguapass/**` (7 Java + `server.js` + `build.sh` + manifiesto), `ApplicationContext`, `DcHelper`, `DcEventCenter`, `WebxdcActivity`, `WebViewActivity`, `AttachmentsContentProvider`, `PersistentBlobProvider`, `DatabaseSecretProvider`, `KeyStoreHelper`, `BaseRpcTransport`, `FFITransport`, `WebRTCClient` (ICE/SDP), `CallService`, `CallCoordinator` (estructura), `InstantOnboardingActivity` (flujo QR/alta), `EditRelayActivity` (estructura), `QrCodeHandler` (estructura), `MainActivity`/`SaleActivity`/`LoginActivity`/`Api`/`Updater` de GuaguaPass.
3. Lectura dirigida del resto de las 439 clases vía firmas, javadoc y patrones de riesgo (`grep` de `addJavascriptInterface`, `Uri.fromFile`, `getUriForFile`, `System.exit`, `new Thread`, `catch (Exception)`, `printStackTrace`, secretos, URLs, permisos, componentes exportados).
4. Revisión de `build.gradle`, `settings.gradle`, los 3 manifiestos (`main`, `foss`, `gplay`), `proguard-rules.pro`, `file_provider_paths.xml` y los 8 workflows.
5. Verificación cruzada de cada afirmación con `grep -n` para citar `archivo:línea`.

**Límites**

- No se compiló el proyecto (requiere NDK r27 + compilar el core Rust; submódulo ausente).
- No se ejecutó la app ni el servidor: los hallazgos son estáticos.
- El core `deltachat-core-rust` (criptografía, SMTP/IMAP, Autocrypt, SecureJoin) no está en el checkout; no se audita.
- Las dependencias de terceros no se auditaron (solo se listan).

---

## 3. Arquitectura y flujo de datos

### 3.1 Vista de capas (ArcaneChat)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ UI (Activities / Fragments / Adapters)                                   │
│ ConversationActivity 1929 · ConversationFragment 1201 · ConversationItem  │
│ ConversationListActivity · MediaPreviewActivity · WebxdcActivity 741      │
│ CallActivity 1030 · ProfileActivity · GroupCreateActivity · ...          │
├──────────────────────────────────────────────────────────────────────────┤
│ Lógica de aplicación / servicios                                         │
│ DcHelper 594 · AccountManager · DcEventCenter · NotificationCenter 967    │
│ JobManager/JobQueue · FetchForegroundService · ForegroundDetector        │
│ CallCoordinator 1604 · CallService 538 · WebRTCClient 1042               │
├──────────────────────────────────────────────────────────────────────────┤
│ Puente con el core                                                       │
│ com.b44t.messenger.DcContext/DcMsg/DcChat (JNI)  ↔  jni/dc_wrapper.c 2073 │
│ chat.delta.rpc.Rpc 1587 (JSON-RPC generado) ↔ FFITransport ↔ DcJsonrpc    │
├──────────────────────────────────────────────────────────────────────────┤
│ Core nativo (submódulo, no presente)                                     │
│ libdeltachat.so (Rust): SMTP/IMAP, Autocrypt, SecureJoin, SQLite          │
├──────────────────────────────────────────────────────────────────────────┤
│ Persistencia local                                                       │
│ files/accounts/<id>/dc.db (SQLite) · blobs · SharedPreferences            │
│ Secreto de BD: AndroidKeyStore (AES/GCM) — crypto/KeyStoreHelper          │
└──────────────────────────────────────────────────────────────────────────┘
```

Dos caminos de comunicación con el core coexisten:

1. **JNI directo** (`DcContext.getMsg(...)`, `dcContext.sendTextMsg(...)`): síncrono, usado en el 80 % de la UI.
2. **JSON-RPC** (`Rpc.java`, generado desde el esquema del core): usado para APIs nuevas (`initTransports`, `iceServers`, `leaveWebxdcRealtime`, backups…). El transporte `FFITransport` envía por `DcJsonrpcInstance.request()` y un hilo `jsonrpcThread` lee respuestas y completa `SettableFuture`.

**Implicación:** hay dos formas de hacer lo mismo (p. ej. `dcContext.checkQr()` vs `rpc.checkQr()`), lo que aumenta la superficie y explica inconsistencias de manejo de errores.

### 3.2 Flujo de un mensaje entrante

```
core Rust (IMAP) ──evento DC_EVENT_INCOMING_MSG──► DcEventEmitter.getNextEvent() [hilo eventThread]
   └─► DcEventCenter.handleEvent()
         ├─► NotificationCenter.notifyMessage()  (canal por chat, privacy pref)
         ├─► ConversationListFragment/ConversationFragment.update
         └─► JobManager / FetchForegroundService (descarga de blobs, webxdc, etc.)
```

`DcEventCenter` entrega a los observadores en el hilo principal vía `Util.runOnMain`, con `try/catch` por observador (decisión defendible: un observador roto no tumba el resto).

### 3.3 Flujo de GuaguaPass (producto aparte)

```
LoginActivity ─ POST /api/login (o /api/auto-register con X-Provision-Key)
   └─ JWT (HS256, 30 días) ──► SharedPreferences ("guagua": user, pass, token)
MainActivity ─ GET /api/config ─► guaguas/rutas/horarios/dims
             ─ GET /api/events?date=&guagua=&hora= ─► SSE (event: state|conflict|mail)
             └ fallback: GET /api/state cada 1000 ms
SaleActivity ─ POST /api/sale ─► 409 si asiento ocupado ─► broadcast SSE a todos
Updater      ─ GET /api/update?version= ─► GET /apk/GuaguaPass.apk ─► ACTION_VIEW (instalador)
server.js    ─ db.json (users, sales, mailboxes) + rebuild automático de la APK con build.sh
```

---

## 4. Mapa de paquetes (LOC reales)

| LOC | Archivos | Paquete | Responsabilidad |
|---:|---:|---|---|
| 18.527 | 70 | `org.thoughtcrime.securesms` | Activities/adaptadores raíz (conversación, lista, ajustes, onboarding, webxdc…) |
| 5.348 | 37 | `…securesms.components` | Widgets (InputPanel, AudioView, Emoji, avatares, cámara…) |
| 5.132 | 49 | `…securesms.util` | Prefs, Util, FileUtils, MediaUtil, IntentUtils, providers de ficheros |
| 4.125 | 7 | `…securesms.calls` | Llamadas: coordinador, actividad, servicio, audio, vídeo |
| 2.493 | 13 | `…securesms.imageeditor.model` | Modelo del editor de imagen (undo/redo, capas) |
| 2.054 | 8 | `…securesms.video.recode` | Recodificación MP4 (vendor) |
| 2.025 | 10 | `…securesms.qr` | QR, deep links, securejoin, backup, relays |
| 1.973 | 2 | `com.codewaves.stickyheadergrid` | Grid con cabeceras (vendor) |
| 1.738 | 41 | `chat.delta.rpc.types` | Tipos del JSON-RPC (generado) |
| 1.724 | 3 | `chat.delta.rpc` | Cliente RPC + transporte |
| 1.724 | 17 | `…securesms.mms` | Adjuntos, Slide/MediaType, Glide |
| 1.719 | 10 | `…securesms.connect` | `DcHelper`, `DcEventCenter`, `AccountManager`, workers, providers |
| 1.500 | 8 | `…securesms.preferences` | Pantallas de ajustes |
| 1.432 | 15 | `…securesms.imageeditor` | Vistas del editor |
| 1.374 | 15 | `com.b44t.messenger` | Bindings JNI (`DcContext`, `DcMsg`, `DcChat`, `DcAccounts`…) |
| 1.362 | 7 | `…securesms.scribbles` | Editor de stickers/imagen |
| 1.145 | 4 | `…securesms.notifications` | `NotificationCenter`, receivers |
| 1.042 | 1 | `…securesms.webrtc` | `WebRTCClient` |
| 943 | 3 | `…securesms.relay` | Alta/edición de relays |
| resto | ~180 | varios | utilities, crypto, database, providers, service, jobmanager, etc. |

Totales: **446 archivos Java de producción, 72.831 líneas** (439 / 71.117 en `src/main`, 7 / 1.714 en `guaguapass`). Añadiendo `src/androidTest` (5) y las variantes `foss`/`gplay` (2) se llega a los 454 archivos `.java` del repo.

---

## 5. Análisis paquete por paquete

### 5.1 `org.thoughtcrime.securesms` (raíz, 70 archivos)

Concentra las pantallas principales y **tres "god classes"**:

- **`ConversationActivity.java` (1.929 líneas, ~160 métodos)**: creación/edición de mensajes, adjuntos, audio, stickers, webxdc, llamadas, ajustes del chat, borradores, permisos, eventos del core. Mezcla UI, red y reglas de negocio; cualquier cambio en el flujo de envío obliga a tocar un archivo de 2.000 líneas. *Debería* descomponerse en `ComposeController` + `AttachmentController` + `ChatSettingsController`.
- **`ConversationFragment.java` (1.201)** y **`ConversationItem.java` (1.077)**: lista de mensajes y binding de cada ítem (multimedia, reacciones, citas, estados). Riesgo típico: `onBindViewHolder` con trabajo síncrono (consultas al core) y listeners no liberados.
- **`ConversationListActivity.java` (761)**: incluye lógica de negocio (alta de relay, QR, "conectividad", migraciones) y mensajes hardcodeados.

Archivos notables y observaciones:

| Archivo | Líneas | Observación |
|---|---:|---|
| `ApplicationContext.java` | 447 | Inicialización global (ver §7 S-06, S-07). Usa `initLock.wait()` y un `eventThread` propio; el handler de crash envía logcat a un correo fijo. |
| `WebxdcActivity.java` | 741 | WebView con `addJavascriptInterface` + CSP + proxy nulo (§7 S-01). |
| `WebViewActivity.java` | 359 | Base de WebView: esquemas permitidos, confirmación de apertura de enlaces, desactiva Safe Browsing (intencional, comentado). Bien resuelto en general. |
| `InstantOnboardingActivity.java` | 638 | Alta instantánea chatmail; contiene la lógica `resolveProviderQr()` (§7 S-05) y URLs hardcodeadas (`arcanechat.me`, `chatmail.at/relays`). |
| `MediaPreviewActivity.java` | 750 | Visor multimedia; lógica heredada (Signal). |
| `ShareActivity.java` | 329 | Recibe `SEND`/`SEND_MULTIPLE`/`mailto`; flujo `EXTRA_EMAIL` → crea contacto y chat (ver nota en §8 B-12). |
| `FullMsgActivity.java` | 265 | HTML de mensajes en WebView con JS desactivado y `allowFileAccess=false` (correcto). |
| `LogViewActivity/LogViewFragment` | 116 / 236 | `Runtime.exec("logcat …")` sin sanitizar; el log es la fuente del crash report. |
| `WebxdcStoreActivity.java` | 161 | Carga `https://webxdc.org/apps/`; intercepta enlaces y valida esquema (correcto). |
| `WelcomeActivity/InstantOnboardingActivity` | 405 / 638 | Rutas de creación de cuenta; aquí convive `DEFAULT_CHATMAIL_HOST = "arcanechat.me"`. |

### 5.2 `…securesms.connect` (puente con el core)

| Archivo | Líneas | Notas |
|---|---:|---|
| `DcHelper.java` | 594 | Fachada estática del core. **`public static final HashMap<String,String> sharedFiles`** (línea 253) es estado global mutable sin sincronización, clave = nombre de archivo y **nunca se limpia** → fuga de memoria y posibilidad de servir un blob de otra cuenta con el mismo nombre a través de `AttachmentsContentProvider`. `getBlobdirFile()` usa `new Date().getTime()` para desambiguar (colisiones posibles). `openForViewOrShare()` construye URIs `content://<pkg>.attachments/<blob>/<nombre>` y registra el blob en `sharedFiles`. |
| `DcEventCenter.java` | ~180 | `Hashtable` + `LOCK`; entrega asíncrona al hilo principal. Observadores no siempre se eliminan → fugas. No filtra eventos por cuenta: un evento de la cuenta A puede llegar a un observador registrado para la cuenta actual B (riesgo de UI inconsistente al cambiar de cuenta). |
| `AccountManager.java` | ~230 | Migración a `DcAccounts`, cambio de cuenta, `setDcContext`. |
| `AttachmentsContentProvider.java` | 140 | `openFile()` sólo valida que el nombre esté en `sharedFiles`; no comprueba `getCanonicalPath()` dentro de `blobdir`. `getType()`/`query()` asumen ≥2 segmentos → `IndexOutOfBoundsException` con URIs malformadas. |
| `NetworkStateReceiver`, `ForegroundDetector`, `KeepAliveService`, `FetchWorker` | — | Vigilancia de conectividad y trabajos periódicos (`WorkManager`). |

### 5.3 `…securesms.calls` + `…securesms.webrtc` (4.125 + 1.042 líneas)

- `CallCoordinator` (1.604): singleton con `Context` de aplicación, `MutableLiveData` para estado de llamada, `Handler` principal, `ExecutorService` de un hilo, integración con `androidx.core.telecom` (`CallsManager`), notificaciones propias y `CallService` en primer plano. Es el archivo más complejo del proyecto después de `ConversationActivity`.
- `CallService` (538): `startForeground` con tipo `camera|microphone`, obtiene ICE servers del core y los pasa a `WebRTCClient`; **registra el JSON completo de servidores ICE (incluye `username`/`credential` de TURN)** en `Log.d` (línea 111).
- `WebRTCClient` (1.042): `PeerConnectionFactory`, `RTCConfiguration` con `MAXBUNDLE`, `rtcpMuxPolicy=REQUIRE`, `UNIFIED_PLAN`, señalización por `DataChannel`, espera de candidatos `relay`/`srflx` con latches y `Thread.sleep(150)` en `waitForEnoughIce()`.
  - `parseIceServers()` acepta cualquier `urls` (incluido `http://` o esquemas arbitrarios) y credenciales en claro desde el core; no valida longitud.
  - `waitForEnoughIce()` se invoca desde hilos de señalización; el `Thread.sleep` bloquea el hilo (aceptable si es de fondo, pero no está documentado).
- `CallActivity` (1.030), `CallViewModel` (474), `MediaStreamManager`, `AudioDevicePickerDialog`: UI de llamada, selector de audio, wake-lock de proximidad.

Riesgo principal: superficie grande con permisos `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_CAMERA/MICROPHONE` y `USE_FULL_SCREEN_INTENT`; las comprobaciones de permiso están presentes (`hasMicrophonePermission`, `hasCameraPermission`, notificaciones) pero el estado se comparte en un singleton, lo que complica el testeo.

### 5.4 `…securesms.notifications`

`NotificationCenter` (967 líneas) es el archivo más delicado en privacidad: construye notificaciones por chat con canales versionados (`ch_msg`, versión "5"), agrupa resúmenes (`ID_MSG_SUMMARY`), respeta `Prefs.getNotificationPrivacy()` y evita notificar chats visibles (`updateVisibleChat`). **Por defecto la privacidad es `"all"`** (`Prefs.java:123-126`), es decir, remitente y texto visibles en pantalla bloqueada.

`RemoteReplyReceiver` y `MarkReadReceiver` son `exported=false` (correcto). `InChatSounds` aplica `MIN_AUDIBLE_PERIOD_MILLIS = 2 s`.

### 5.5 `…securesms.qr`, `relay`, `proxy`

- `QrCodeHandler` (453): enrutador de QR. Flujos: securejoin (`handleOnlySecureJoinQr`), relay (`handleOnlyAddRelayQr` → pide autorización vía `relayLockLauncher`), proxy (`handleProxyQr`), backup (`handleBackupQr`) y "por defecto".
- `QrActivity/QrScanFragment/CustomCaptureManager`: cámara + ML Kit/ZBar (heredado).
- `relay/EditRelayActivity` (545): formulario de alta de relay (email/password, IMAP/SMTP, certificados); `RelayListActivity`, `RelayListAdapter`.
- `proxy/ProxySettingsActivity`: acepta `ss://`, `socks5://`, `http://` desde otras apps (**exported**, con filtro de esquema). Es una superficie de *phishing* aceptable (el usuario ve el diálogo y confirma), pero conviene validar el host y no permitir credenciales en la URL.

### 5.6 `…securesms.mms` (adjuntos) y `components`

`AttachmentManager` (803): `Stub`/`ThumbnailView`/`AudioView`/`DocumentView`/`WebxdcView`/`VcardView`, cola `garbage` de URIs a limpiar y `SlideDeck`. `Slide` y subclases (`ImageSlide`, `VideoSlide`, `GifSlide`, `AudioSlide`, `DocumentSlide`, `StickerSlide`, `VcardSlide`) calculan tamaños y tipos. Correcto en general; el punto débil es que el tamaño máximo depende de `MediaConstraints` y del core, no de validación local del archivo.

`components/InputPanel` (480), `ComposeText`, `AttachmentTypeSelector`, `MicrophoneRecorderView`: entrada de texto/audio; sin problemas evidentes.

### 5.7 `…securesms.imageeditor`, `scribbles`, `video`

Código heredado de Signal (licencia GPL, atribuido en cabeceras): modelo de capas con undo/redo (`EditorModel` 849), render de texto multlínea (394), `MultiLineTextRenderer`. `video/recode` es código vendor de recodificación MP4 (`VideoRecoder` 701, `MP4Builder` 437). **No se debe tocar sin necesidad**: alto riesgo de regresión y poca cobertura de tests.

### 5.8 `…securesms.util` (49 archivos)

- `Prefs` (373): **todas** las preferencias en `SharedPreferences` por defecto, incluidos `pref_database_encrypted_secret_<id>` (cifrado) y `pref_database_unencrypted_secret_<id>` (sólo API < 23).
- `Util` (417): threading (`runOnMain`, `runOnBackground`), invitaciones (`INVITE_DOMAIN = "i.delta.chat"`), helpers.
- `SaveAttachmentTask` (409): guardado en almacenamiento; usa MediaStore, permisos runtime.
- `FileUtils`, `MediaUtil`, `IntentUtils`, `ShareUtil`, `Linkifier`, `MarkdownUtil`: utilidades con saneado de nombres (`sanitizeFilename`), apertura de enlaces y compartición.
- `util/guava` (6 archivos, 894 líneas): copia *vendored* de Guava (`Optional`, `Preconditions`, `Function`…) — deuda técnica: podría sustituirse por `java.util.Optional`/`Objects`.
- `util/concurrent`: `SettableFuture` propio (usado por el transporte RPC).

### 5.9 `…securesms.crypto`, `database`, `providers`

- `KeyStoreHelper`: AES/GCM con clave en `AndroidKeyStore` (alias `DeltaSecret`), `SealedData` serializado por Jackson. **Correcto** (patrón Signal).
- `DatabaseSecretProvider`: secreto aleatorio de 32 bytes por cuenta, sellado con Keystore en API ≥ 23; en API < 23 queda en claro (limitación del SO, documentada).
- `PersistentBlobProvider` (356): *no es un `ContentProvider`* pese al nombre; genera URIs sintéticas `content://org.thoughtcrime.securesms/capture-new/...` que sólo tienen sentido dentro de la app. Usa `System.currentTimeMillis()` como id → colisión y sobrescritura si se crean dos blobs en el mismo milisegundo. `delete()` hace `new File(uri.getPath()).delete()` para URIs externas (borrado de ruta arbitraria si alguna vez se llama con URI no confiable).
- `SingleUseBlobProvider`: caché en memoria `HashMap<Long, byte[]>`; `getStream` elimina la entrada; correcto salvo que no hay límite de tamaño.

### 5.10 `…securesms.service`, `jobmanager`, `geolocation`, `audio`

Servicios en primer plano con tipos declarados (`dataSync`, `mediaPlayback`, `camera|microphone`). `JobManager`/`JobQueue`/`JobConsumer` implementan una cola propia con `requirements` (red, carga). `BootReceiver` y `PanicResponderListener` están **exported sin permiso**, pero hoy son *no-op* (ver §7 S-09).

`AudioPlaybackService` es `exported=true` con `MediaSessionService` (necesario para controles externos); aceptable si los `MediaItem` se validan.

### 5.11 `jni/dc_wrapper.c` (2.073 líneas)

Puente JNI escrito a mano. Puntos observados:

- `char_ref__()` obtiene bytes con `String.getBytes("UTF-8")` (evita bug de `GetStringUTFChars` con emojis en ROMs antiguas) y `strndup`. **No hay `ExceptionCheck` en todo el archivo** (0 ocurrencias): si `getBytes` lanza (OOM), `stringJbytes` es `NULL` y `GetArrayLength(NULL)` provoca aborto nativo.
- Se crean 4 `NewGlobalRef` y **no se libera ninguno** (`DeleteGlobalRef`: 0). Son dos pares `String.class` / `"UTF-8"`, cacheados en estáticos: la fuga es pequeña y acotada, pero el patrón es incorrecto y no es thread-safe (dos hilos pueden inicializar a la vez y filtrar una referencia global).
- `free()` aparece 8 veces; `strndup` asigna: el pareo manual `CHAR_UNREF` es correcto en los casos revisados, pero es frágil ante nuevos métodos.
- Recomendación: añadir `ExceptionCheck`, `DeleteGlobalRef` en `JNI_OnUnload`, y usar `pthread_once`/`std::call_once`.

### 5.12 `com.b44t.messenger` (bindings) y `chat.delta.rpc`

`DcContext` (414) mezcla constantes de eventos/estados y métodos nativos; el paso de `JSONObject`/listas entre Java y Rust se hace por cadenas. `DcMsg`, `DcChat`, `DcContact`, `DcAccounts`, `DcProvider`, `DcLot`, `DcEvent` son envoltorios finos.

`Rpc.java` (1.587) es **código autogenerado** (`scripts/update-rpc-bindings.sh`): no editar a mano; excluido de Spotless. `BaseRpcTransport` (ver §7 S-03) es la pieza crítica.

### 5.13 Recursos y assets

- `assets/help/<idioma>/help.html` (16 idiomas): ayuda local, abierta en WebView.
- `assets/webxdc/maps.xdc`: app de mapas empaquetada (Leaflet incluido, 147 KB de JS *vendored*).
- `assets/calls/index.html`: wrapper de llamadas.
- 417 PNG + 64 WebP: peso alto; candidatos a vectorizar/WebP.

---

## 6. Análisis archivo por archivo (los 40 más relevantes)

> "Riesgo" es una estimación de probabilidad × impacto de introducir fallos al modificarlo.

| # | Archivo | Líneas | Rol | Observaciones / riesgo |
|---:|---|---:|---|---|
| 1 | `ConversationActivity.java` | 1.929 | Pantalla de conversación/composición | God class; ~160 métodos; mezcla UI, permisos, adjuntos, audio, webxdc. **Riesgo alto** |
| 2 | `calls/CallCoordinator.java` | 1.604 | Orquestador de llamadas | Singleton con estado de llamada y `LiveData`; mucha sincronización manual. **Riesgo alto** |
| 3 | `chat/delta/rpc/Rpc.java` | 1.587 | Cliente RPC generado | No editar; si se actualiza el core hay que regenerar (`update-rpc-bindings.sh`) |
| 4 | `com/codewaves/stickyheadergrid/*` | 1.973 | Vendor | Sin mantenimiento; candidato a sustituir por `ConcatAdapter` |
| 5 | `ConversationFragment.java` | 1.201 | Lista de mensajes | Bindings pesados; listeners de core por ítem |
| 6 | `ConversationItem.java` | 1.077 | Ítem de mensaje | Multimedia + reacciones + citas; alto acoplamiento |
| 7 | `webrtc/WebRTCClient.java` | 1.042 | Motor WebRTC | ICE con latches y sleeps; parseo de ICE servers sin validación |
| 8 | `calls/CallActivity.java` | 1.030 | UI de llamada | Wake-locks, permisos, full-screen intent |
| 9 | `notifications/NotificationCenter.java` | 967 | Notificaciones | Privacidad por defecto `all`; canales versionados |
| 10 | `imageeditor/model/EditorModel.java` | 849 | Modelo editor | Heredado Signal; sin tests |
| 11 | `mms/AttachmentManager.java` | 803 | Adjuntos | Gestión de `garbage` y SlideDeck; cuidado con fugas de URIs |
| 12 | `ConversationListActivity.java` | 761 | Lista de chats | Contiene lógica de relay/QR/conectividad y textos fijos |
| 13 | `MediaPreviewActivity.java` | 750 | Visor | Heredado; permisos de almacenamiento |
| 14 | `WebxdcActivity.java` | 741 | Contenedor WebXDC | Puente JS + CSP + proxy nulo (ver S-04) |
| 15 | `video/recode/VideoRecoder.java` | 701 | Recodificación | Vendor; sensible a dispositivos |
| 16 | `InstantOnboardingActivity.java` | 638 | Alta chatmail | `resolveProviderQr()` (S-05); URLs fijas |
| 17 | `connect/DcHelper.java` | 594 | Fachada del core | Estado estático `sharedFiles` (S-08); `getBlobdirFile` con timestamps |
| 18 | `com/codewaves/.../StickyHeaderGridAdapter.java` | 593 | Vendor | — |
| 19 | `util/ViewUtil.java` | 591 | Utilidades de vista | Insets/animaciones; bajo riesgo |
| 20 | `relay/EditRelayActivity.java` | 545 | Alta de relay | Credenciales IMAP/SMTP en pantalla; validación de servidores |
| 21 | `calls/CallService.java` | 538 | FGS de llamada | **Log con credenciales TURN** (S-02) |
| 22 | `BaseConversationListFragment.java` | 513 | Base de listas | Acciones contextuales/relay |
| 23 | `imageeditor/ImageEditorView.java` | 488 | Vista del editor | Heredado |
| 24 | `ConversationAdapter.java` | 483 | Adaptador | Reciclado y tipos de ítem |
| 25 | `components/InputPanel.java` | 480 | Panel de entrada | Teclado/emoji/adjuntos |
| 26 | `ProfileActivity.java` | 480 | Perfil | Edición de nombre/avatar |
| 27 | `calls/CallViewModel.java` | 474 | Estado de llamada | `LiveData`, coroutines |
| 28 | `GroupCreateActivity.java` | 469 | Crear grupo | Verificación de contactos |
| 29 | `ProfileAdapter.java` | 454 | Adaptador de perfil | — |
| 30 | `qr/QrCodeHandler.java` | 453 | Enrutador QR | Securejoin/relay/proxy/backup; decisiones de confianza |
| 31 | `ApplicationContext.java` | 447 | Arranque global | **Deadlock posible (S-07) y crash handler con logcat (S-06)** |
| 32 | `video/recode/MP4Builder.java` | 437 | Vendor | — |
| 33 | `chat/delta/rpc/types/EventType.java` | 423 | Tipos RPC | Generado |
| 34 | `util/Util.java` | 417 | Utilidades | Threading global |
| 35 | `permissions/Permissions.java` | 415 | Permisos | Flujo runtime; correcto pero central |
| 36 | `com/b44t/messenger/DcContext.java` | 414 | Bindings | Constantes + nativos |
| 37 | `util/SaveAttachmentTask.java` | 409 | Guardar adjuntos | MediaStore/permisos |
| 38 | `WelcomeActivity.java` | 405 | Bienvenida | Rutas de alta |
| 39 | `util/guava/Preconditions.java` | 399 | Vendor | Deuda técnica |
| 40 | `providers/PersistentBlobProvider.java` | 356 | Blobs temporales | Id por timestamp; URI sintética |

Los 406 archivos restantes están inventariados en `docs/analisis/inventario-archivos.csv` (columna `capa`), y su análisis agregado aparece en §5.

---

## 7. Auditoría de seguridad

> Escala: 🔴 Crítico · 🟠 Alto · 🟡 Medio · 🔵 Bajo · ⚪ Informativo

### S-01 🟡 `addJavascriptInterface` sin restricción de origen + `interceptRequest` sin validar host

**Evidencia:** `WebxdcActivity.java:242-254` (JS habilitado, `addJavascriptInterface(new InternalJSApi(), "InternalJSApi")`), `:391-446` (`interceptRequest` resuelve blobs por `path` sin comprobar que la petición vaya al host `acc<id>-msg<id>.localhost`).

**Impacto:** con `internet_access=true` (`setBlockNetworkLoads(false)`), la app webxdc puede navegar el frame superior a un sitio remoto; ese sitio queda con acceso al objeto `InternalJSApi` (`sendToChat`, `sendStatusUpdate`, `getStatusUpdates`, `ttsSpeak`, `entrar/salir del canal realtime`). El diseño de Delta Chat mitiga parcialmente porque la app webxdc es código que el usuario ejecuta, pero el puente **no debería estar expuesto a orígenes remotos**.

**Recomendación:**
```java
// WebxdcActivity.interceptRequest
String host = Uri.parse(rawUrl).getHost();
if (host == null || !host.equals(baseURLHost)) return blockedResponse(); // 403 local
// y en el WebViewClient:
@Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
  if (!r.getUrl().getHost().equals(baseURLHost)) { openExternally(r.getUrl()); return true; }
  return false;
}
```

### S-02 🟡 Credenciales TURN/ICE en logcat

**Evidencia:** `calls/CallService.java:110-113` — `Log.d(TAG, "ICE servers fetched: " + iceServersJson)`, donde el JSON incluye `"username"` y `"credential"`.

**Impacto:** cualquier app con acceso a logcat (adb, root, ROMs antiguas, herramientas de soporte) obtiene credenciales de relay de las llamadas. El *crash handler* además vuelca `grabLogcat()` en el correo.

**Recomendación:** eliminar el log o registrar sólo número y tipos (`Log.d(TAG, "ICE servers: " + count + " (turn=" + hasTurn + ")")`). Auditar `WebRTCClient.java:389,434,470` (SDP y ICE candidates) para no incluir `ufrag`/`pwd`.

### S-03 🟠 Transporte RPC: hilo lector sin recuperación y `future.get()` sin timeout

**Evidencia:** `chat/delta/rpc/BaseRpcTransport.java:47-64` (`future.get()` sin `get(timeout)`), `:68-81` (`while(true) { processResponse(); }` con único `catch (JsonProcessingException) { printStackTrace(); }`), `:83-95`.

**Impacto:**
- Si `getResponse()` lanza una `RuntimeException` (p. ej. `UnsatisfiedLinkError`, error nativo, OOM), el hilo muere y **todas las llamadas RPC posteriores quedan bloqueadas para siempre** (`future.get()` sin timeout) → ANR y funciones inutilizadas (backups, llamadas, webxdc realtime).
- Si la respuesta no es JSON, `printStackTrace()` se ejecuta en bucle a máxima velocidad (CPU).
- El hilo no es `daemon`, mantiene el proceso vivo.

**Recomendación:** `catch (Throwable)` con salida controlada + completar todas las *futures* con excepción; `future.get(30, SECONDS)`; back-off en errores de parseo; `setDaemon(true)`; `requestFutures.remove(id)` en `finally` del `innerCall`.

### S-04 🟢 (positivo) WebView de mensajes HTML

`FullMsgActivity.java:67-70`: JS desactivado, sin acceso a ficheros. `WebViewActivity` desactiva Safe Browsing (privacidad) y sólo permite esquemas conocidos. Buen patrón.

### S-05 🟡 `dcaccount:` externo provoca petición de red a un host arbitrario

**Evidencia:** `InstantOnboardingActivity.java:595-608`:
```java
String host = qrCode.substring((DCACCOUNT + ":").length());
String candidate = "https://" + host + "/qrcode.png";
if (dcContext.checkQr(candidate).getState() == DcContext.DC_QR_ACCOUNT) return candidate;
```
El manifiesto expone la actividad con `VIEW` + `BROWSABLE` + `scheme=DCACCOUNT` (`AndroidManifest.xml:368-380`).

**Impacto:** un enlace web `dcaccount://host-arbitrario` hace que el dispositivo **descargue** `https://host-arbitrario/qrcode.png` (fuga de IP/UA, posible *fingerprinting* y *tracking*), y la configuración de la cuenta se toma de un QR remoto no verificado. Además `checkQr()` es I/O de red: si se llamara en el hilo principal congelaría la UI.

**Recomendación:** validar el host con una *allowlist* o al menos con patrón DNS + confirmación explícita al usuario; no resolver QRs remotos automáticamente; ejecutar siempre en background con timeout.

### S-06 🟠 El crash handler exfiltra logcat y datos de la app

**Evidencia:** `ApplicationContext.java:160-186`:
```java
errorMsg += "\n" + LogViewFragment.grabLogcat();       // hasta 10.000 líneas de log
intent.putExtra(Intent.EXTRA_EMAIL, "adb@merlinux.eu"); // destinatario fijo
startActivity(chooser);
ApplicationContext.this.finalize();                     // llamada inútil (deprecated)
```
**Impacto:** el logcat contiene direcciones, nombres de chats, rutas de ficheros, mensajes de error de IMAP/SMTP, IDs de cuenta y (por S-02) credenciales TURN. El usuario puede pulsar "enviar" en el selector sin saber qué se envía. `finalize()` no hace nada (y está deprecado desde Java 9).

**Recomendación:** (a) mostrar el texto al usuario antes de compartir y permitir copiarlo; (b) redactar patrones (emails, teléfonos, tokens) ; (c) eliminar `finalize()`; (d) no enviar a una dirección personal fija por defecto.

### S-07 🟠 Posible deadlock de inicialización

**Evidencia:** `ApplicationContext.java:78-84` (`ensureInitialized()` → `initLock.wait()` en bucle) y `:296-301` (`catch (Exception e) { isInitialized = true; notifyAll(); throw … }`).

**Impacto:** `System.loadLibrary("native-utils")`, `new DcAccounts(...)`, `Rpc`, `migrateToDcAccounts` o la apertura de cuentas pueden lanzar `Error`/`Throwable` (`UnsatisfiedLinkError`, `OutOfMemoryError`, `ExceptionInInitializerError`). El `catch (Exception)` no los captura → `isInitialized` queda `false` y **todo** hilo que llame a `getDcAccounts()`, `getRpc()`, `getDcContext()`, `getEventCenter()`, `getNotificationCenter()` o `getLocationManager()` espera indefinidamente → ANR permanente tras cada arranque.

**Recomendación:**
```java
} catch (Throwable t) {
  Log.e(TAG, "Fatal init", t);
  synchronized (initLock) { isInitialized = true; initLock.notifyAll(); }
  throw new RuntimeException(t);
}
```
Además: timeout en `ensureInitialized()` y estado `initFailed` para que la UI muestre un error en lugar de colgarse.

### S-08 🟡 Estado global `sharedFiles` en `DcHelper`

**Evidencia:** `DcHelper.java:253` (`public static final HashMap<String,String> sharedFiles = new HashMap<>();`), `:300` (`sharedFiles.put(file.getName(), mimeType)`), consumido en `AttachmentsContentProvider.java:45-51,93-102`.

**Impacto:**
1. **Sin sincronización** (`HashMap` accedido desde hilos de UI y de `ContentProvider`) → corrupción posible en concurrencia.
2. **Clave = nombre de archivo**: al compartir un blob de la cuenta A, cualquier URI con ese nombre queda autorizada en la cuenta B (el `blobdir` se resuelve con la cuenta *actual*), pudiendo servir un archivo distinto con el mismo nombre a la app receptora.
3. **Nunca se limpia** → crecimiento indefinido y autorizaciones permanentes para el proceso.

**Recomendación:** `ConcurrentHashMap` con clave `<accountId>/<blobName>` y TTL, o mejor: firmar el URI con un token aleatorio por concesión y validar `canonicalPath` dentro de `blobdir`.

### S-09 🔵 Componentes exportados sin permiso

**Evidencia:** `AndroidManifest.xml:545-560` — `BootReceiver` (`BOOT_COMPLETED` + `org.thoughtcrime.securesms.RESTART`) y `PanicResponderListener` (`info.guardianproject.panic.action.TRIGGER`), ambos `exported="true"` sin `android:permission`.

**Impacto:** hoy son *no-op* (`BootReceiver.onReceive` sólo loguea; `PanicResponderListener` no hace nada), pero cualquier app puede enviar los broadcasts. Si en el futuro se añade lógica (p. ej. reiniciar I/O o bloquear la app), se convierte en un vector.

**Recomendación:** `exported="false"` para `BootReceiver` (no lo necesita) y proteger `PanicResponderListener` con una `permission` de firma, o eliminarlo.

### S-10 🔵 `FileProvider` con `root-path /storage/`

**Evidencia:** `src/main/res/xml/file_provider_paths.xml`:
```xml
<root-path name="external_root" path="/storage/" />
```
Proveedor: `authorities="${applicationId}.fileprovider"`, `exported="false"`, `grantUriPermissions="true"`.

**Impacto:** cualquier `File` bajo `/storage/` puede convertirse en `content://` y concederse. No hay hoy una ruta evidente en la que un tercero controle el `File`, pero amplía innecesariamente la superficie (p. ej. si un flujo futuro comparte "el archivo que indique el mensaje").

**Recomendación:** eliminar `root-path` y limitar a `cache-path`/`external-files-path` necesarios.

### S-11 🔵 Instalación de APKs desde chats

**Evidencia:** permiso `REQUEST_INSTALL_PACKAGES` (`AndroidManifest.xml:67`); `DcHelper.startActivity()` (`:378-390`) detecta `application/vnd.android.package-archive` y redirige a "instalar apps desconocidas".

**Impacto:** un contacto puede enviar un APK; la app ofrece abrirlo. El sistema exige confirmación y el usuario debe habilitar el permiso, pero es un vector de *phishing* clásico.

**Recomendación:** mantener el aviso actual y añadir diálogo explícito ("este archivo es una app; ¿abrir el instalador?") antes de saltar a Ajustes.

### S-12 🔵 Privacidad de notificaciones por defecto

**Evidencia:** `Prefs.java:123-126` → `getStringPreference(context, NOTIFICATION_PRIVACY_PREF, "all")`.

**Impacto:** remitente + texto del mensaje visibles en pantalla bloqueada. Es el comportamiento heredado, pero en una app "privada por diseño" conviene `"contact"` o `"none"` por defecto y onboarding explícito.

### S-13 🔵 `google-services.json` versionado

**Evidencia:** `google-services.json` en la raíz (5 `package_name`: `chat.delta`, `com.github.arcanechat`, `com.github.arcanechat.beta`, `chat.delta.lite`, `chat.delta.lite.beta`) con `api_key` `AIza…`.

**Impacto:** la *API key* de Firebase para Android no es un secreto por diseño (va en el APK), pero versionar el archivo mezcla configuración de terceros (proyecto `delta-chat-fcm`) y facilita el abuso de cuotas si no hay restricciones por SHA-1/paquete. **Recomendación:** rotar/limitar la key en Google Cloud y documentar el origen; no es urgente.

### S-14 ⚪ WebView de WebXDC: `setDatabaseEnabled(true)` + `DomStorage`

**Evidencia:** `WebxdcActivity.java:249-250`.

**Impacto:** cada app webxdc puede persistir datos locales; se comparte el almacenamiento del WebView entre apps del mismo *subdominio* (se usa `acc<id>-msg<msgId>.localhost` para aislar cache). Aceptable; documentar que los datos persisten y que el GC (`WebxdcGarbageCollectionWorker`) no borra el almacenamiento del WebView.

---

## 8. Bugs, defectos y deuda técnica (app principal)

| ID | Severidad | Archivo:línea | Defecto |
|---|---|---|---|
| B-01 | 🟡 | `connect/AttachmentsContentProvider.java:62-65,113-116` | `getType()`/`query()` hacen `getPathSegments().get(1)` sin comprobar tamaño → `IndexOutOfBoundsException` (crash del proceso) con URI de un solo segmento. Validar `segments.size() >= 2`. |
| B-02 | 🟡 | `providers/PersistentBlobProvider.java:81,139,161` | `id = System.currentTimeMillis()` → dos blobs creados en el mismo ms colisionan y se sobrescriben. Usar `AtomicLong` + timestamp o UUID. |
| B-03 | 🔵 | `providers/PersistentBlobProvider.java:29-31` | URIs con autoridad `org.thoughtcrime.securesms` (constante) que no corresponden a ningún `ContentProvider` real: si se comparten con otra app, ésta no puede leerlas. El nombre "Provider" induce a error. |
| B-04 | 🟡 | `connect/DcHelper.java:407-441` | `getBlobdirFile()` desambigua con `new Date().getTime()`; dos llamadas en el mismo ms con el mismo nombre devuelven la misma ruta. |
| B-05 | 🟡 | `connect/DcEventCenter.java:96-130` | Entrega asíncrona a `runOnMain` sin orden garantizado; observadores de "cuenta actual" reciben eventos sin comprobar `accountId`; `removeObservers` recorre listas bajo lock pero los `ArrayList` se leen en otro hilo. |
| B-06 | 🔵 | `ApplicationContext.java:180` | `ApplicationContext.this.finalize()` — método deprecado y sin efecto; código muerto que sugiere una intención equivocada. |
| B-07 | 🔵 | `webrtc/WebRTCClient.java:561` | `Thread.sleep(150)` dentro de `waitForEnoughIce()` (bloquea el hilo llamante). |
| B-08 | 🔵 | `util/Util.java:347-352` | `sleep()` sin gestión de interrupción (traga `InterruptedException`). |
| B-09 | 🔵 | varios | 137 bloques `catch (Exception e)` genéricos y 69 `printStackTrace()`: se pierde el tipo de error y se ensucia logcat. |
| B-10 | 🔵 | `WebxdcActivity.java:212`, `LogViewFragment`, `addToHomeScreen` | Mensajes de UI hardcodeados en inglés ("Webxdc does no longer exist.", "ErrAddToHomescreen: …"), no traducibles. |
| B-11 | 🔵 | `ConversationActivity`, `ConversationFragment`, `ConversationItem` | God classes con >1.000 líneas; violan SRP y dificultan tests. |
| B-12 | 🔵 | `ShareActivity.java:249-263` | `EXTRA_EMAIL` de un `ACTION_SEND` externo crea contacto y chat automáticamente (`createContact(null, addr)`); no valida el formato ni limita a 1 destinatario → un `mailto:` malicioso puede inyectar un contacto (bajo impacto, pero conviene validar con `MailtoUtil`/`Patterns.EMAIL_ADDRESS`). |
| B-13 | 🔵 | `util/guava/*` (894 líneas) | Copia *vendored* de Guava; deuda técnica sustituible por APIs de Java 8. |
| B-14 | 🔵 | `build.gradle:213` | `lint { abortOnError false }`: los errores de Lint no bloquean la compilación; se pierden avisos de seguridad. |
| B-15 | 🔵 | `build.gradle:146-156` | `release` con `minifyEnabled true` (nota del propio archivo contradice el comentario "minification disabled"), sin `shrinkResources`; el `mapping.txt` se publica como artefacto (correcto) pero no hay verificación de que ProGuard no rompa JNI/RPC. |
| B-16 | 🟡 | `.github/workflows/build-apk.yml` | Workflow roto: no hace `checkout` con `submodules: recursive`, no compila el core (`scripts/ndk-make.sh`), ejecuta `assembleDebug` y sube `app/build/outputs/apk/debug/app-debug.apk` (no existe módulo `app/`). Resultado: build/upload fallido en cada push/PR. |

---

## 9. Concurrencia, memoria y rendimiento

1. **Hilos creados a mano**: 21 `new Thread(...)` fuera del `JobManager`/executors (`ApplicationContext:229`, `MainActivity` de GuaguaPass, `InstantOnboardingActivity:551`, `SaleActivity`…). Sin nombre, sin política de cancelación, sin límite.
2. **Estado estático global**: `DcHelper.sharedFiles`, `DatabaseSecretProvider.instances`, `CallCoordinator.instance`, `Prefs` (estático implícito), `Api.BASE` y `Session` en GuaguaPass. Dificulta tests y provoca efectos colaterales entre cuentas.
3. **`ApplicationContext`**: el hilo `eventThread` es `while(true)` con `getNextEvent()` bloqueante; no hay ruta de apagado limpio (el proceso muere). Correcto en Android, pero impide tests de integración.
4. **UI principal**: `runOnUiThread`/`Util.runOnMain` se usan masivamente; los eventos de un `INCOMING_MSG` con muchos adjuntos generan N actualizaciones de UI. No hay *coalescing* visible.
5. **Rendimiento de GuaguaPass**: la rejilla completa (48 asientos + animación `ScaleAnimation` por fila) se reconstruye **en cada `state`** (potencialmente cada segundo en modo polling): `grid.removeAllViews()` + 48 vistas + 12 animaciones. En un teléfono modesto provoca parpadeo y consumo. Solución: actualizar sólo las vistas cuyo `seat` cambió (o `RecyclerView` con `DiffUtil`).
6. **Servidor GuaguaPass**: `persist()` reescribe **todo** `db.json` con `fs.writeFileSync` cada 200 ms de debounce; con histórico de ventas y buzones crece sin límite y bloquea el event loop.
7. **Imágenes**: 417 PNG + 64 WebP; `assets/webxdc/maps.xdc` incluye Leaflet de 147 KB. El APK universal con 4 ABIs es grande; ya se usan *splits* por ABI (bien).

---

## 10. GuaguaPass — análisis completo

### 10.1 Arquitectura real

- **App Android sin Gradle** (`build.sh` con `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`), `minSdk 24`, `targetSdk 34`, sin AndroidX → toda la UI construida programáticamente, sin `Activity` lifecycle-aware.
- **Servidor Node.js sin dependencias** (`http` + `crypto` + `fs`), `db.json` como base de datos, SSE para *push* y *polling* de 1 s como *fallback*.
- **Identidad "estilo chatmail"**: usuario `cccvcv@arcanechat.me` derivado localmente + contraseña de 20 caracteres; auto-registro con `X-Provision-Key`.

### 10.2 Hallazgos

| ID | Severidad | Evidencia | Hallazgo |
|---|---|---|---|
| G-01 | 🔴 | `guaguapass/out/guagua.keystore`, `build.sh:39-46` (`-storepass guagua123`), `out/*.apk`, `out/*.class`, `out/classes.dex` | **Clave de firma y contraseña públicas en el repositorio**, junto con los artefactos compilados. Cualquiera puede firmar un APK que Android aceptará como actualización legítima de la app instalada. |
| G-02 | 🔴 | `Updater.java:55-75`; `AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`); `Api.java:64-83` | **Cadena de actualización insegura.** El `sha256` que se compara viene de la misma respuesta que la URL (`server.js:330-337`): no protege ante un servidor comprometido o MITM. No se verifica el certificado de firma del APK. `Uri.fromFile(dest)` + `startActivity(ACTION_VIEW)` en `targetSdk 34` lanza **`FileUriExposedException`** (StrictMode), por lo que la auto-actualización **falla en la práctica**; y si se "arregla" con FileProvider sin añadir verificación de firma, queda explotable. |
| G-03 | 🟠 | `Identity.java:43` (`PROVISION_KEY = "guagua-provision-key"`), `server.js:34,105-108` | **Secreto compartido embebido en el APK** y auto-registro abierto por defecto (`PROVISION_OPEN` = true si `GUAGUA_PROVISION !== '0'`). Extraer la clave del APK es trivial (`apktool`/`strings`). Cualquiera crea cuentas; sin captcha, sin límite, sin PoW. |
| G-04 | 🟠 | `server.js:353-358` | `POST /api/user` **sin autenticación** permite crear gestores con cualquier nombre y contraseña (incluso de 1 carácter: `createUser` sólo exige no vacío). |
| G-05 | 🟠 | `server.js:154-170,360-369` | JWT: `SECRET` aleatorio por proceso si no hay env (tokens invalidados en cada reinicio; imposible escalar a varias instancias), comparación `s !== expect` **no constante** (timing), sin `jti`/revocación, expiración 30 días, sin `rate-limit` en `/api/login` (fuerza bruta ilimitada), sin bloqueo por cuenta. |
| G-06 | 🟠 | `server.js:202-213` | `statePayload()` devuelve `customer`, `phone`, `destino`, `precio`, `user_name` de **todas** las ventas del canal a **cualquier** usuario autenticado: fuga de datos personales de pasajeros entre gestores (y hacia cualquiera que se auto-registre con G-03). |
| G-07 | 🟠 | `server.js:173-179` | `readBody()`: si el cuerpo supera 1 MB hace `req.destroy()` pero **nunca resuelve la promesa** → petición colgada (DoS trivial). Además `JSON.parse` silencioso a `{}`. |
| G-08 | 🟠 | `server.js:475-479` | El arranque imprime `PROVISION_KEY` en consola (`clave: ${PROVISION_KEY}`) y el servidor escucha en `0.0.0.0` **sin TLS**. Toda la autenticación (usuario/contraseña, JWT, datos de pasajeros) viaja en claro si no se pone un proxy TLS delante. |
| G-09 | 🟡 | `server.js:98-130` | **Auto-rebuild en producción**: `GET /api/update` ejecuta `execFile('/usr/bin/env', ['bash', BUILD_SH])` en el host, con `timeout 600 s`. Si el fuente es modificable (o el servidor corre con permisos de escritura sobre el repo), se convierte en ejecución de código; además acopla el servidor web a un SDK de Android. |
| G-10 | 🟡 | `server.js:65-69,141-147` | `bumpVersion()` colapsa el parche (`1.0.1` → `1.1`, perdiendo `.0`), y `cmpVer` compara 3 componentes: versiones inconsistentes pueden impedir detectar actualizaciones. `db.json` se escribe **no atómicamente** (`writeFileSync` directo) y si se corrompe, `catch {}` reinicia `db` a vacío **perdiendo todas las ventas**. |
| G-11 | 🟡 | `Identity.java:61-77` | Entropía del login baja (~`95^3 ≈ 857.000` ≈ 20 bits) y sesgo de módulo en la contraseña (`b[i] % 36`). Si falta `id_pass` pero hay `id_login`, se **genera una identidad nueva** y se pierde la cuenta anterior. Credenciales (`user`, `pass`, `token`) en `SharedPreferences` **en claro**. |
| G-12 | 🟡 | `Identity.java:32`, `Api.java:14` | `SERVER_URL = "https://arcanechat.me"` como **servidor por defecto**: no es el servidor de GuaguaPass. El primer arranque siempre fallará (o, peor, enviará credenciales a un tercero si ese host llegase a exponer rutas homónimas). Además el README y la UI se presentan como "método ArcaneChat/chatmail", usando un dominio ajeno (riesgo de marca/confusión). |
| G-13 | 🟡 | `MainActivity.java:555-595` | SSE: `sseAlive = true` **antes** de conectar (el indicador muestra "● En vivo" aunque falle); `stopStream()` sólo pone un flag, no interrumpe `read()` (hasta 30 s de hilo vivo); al cambiar de canal se acumulan hilos. |
| G-14 | 🟡 | `MainActivity.java:246` y `SaleActivity.java:37,134-146` | El botón "+ REGISTRAR VENTA" abre el formulario con `seat = -1`; el servidor responde `400 asiento invalido` (`server.js:440`). Flujo roto (funcionalidad "venta rápida" inexistente). |
| G-15 | 🟡 | `SaleActivity.java:87-93` | `contentRoot.addView(free, 2)` inserta el campo "Destino" libre en el índice 2 (entre la etiqueta y el campo de nombre), con lo que el formulario offline queda descolocado. |
| G-16 | 🔵 | `Api.java:147-163` | Parser JSON artesanal (`jstr`) que no soporta `\uXXXX`, escapes `\t`/`\r`, ni anidamiento; `Updater.parse()` usa `json.contains("\"update\":true")`; `MainActivity.extractNum` ignora números negativos bien formados y decimales. Cualquier cambio de formato del servidor rompe la app de forma silenciosa. |
| G-17 | 🔵 | `Api.java:102-144`, `MainActivity.java:559` | Hilos no ligados al ciclo de vida (`new Thread` en cada acción); si se rota/pausa la actividad, los callbacks siguen ejecutándose (`runOnUiThread` sobre actividad destruida). Sin `onDestroy`. |
| G-18 | 🔵 | `build.sh`, `AndroidManifest.xml` | Sin `android:networkSecurityConfig` (un `http://` escrito por el usuario falla sin explicación con `targetSdk 34`); `allowBackup=false` (bien); `android:debuggable` no definido (bien); sin `android:usesCleartextTraffic` explícito. |
| G-19 | 🔵 | `server.js:411-435` | Correo interno sin límites: cualquier usuario autenticado envía a cualquier otro (spam), el error "destinatario no encontrado" permite **enumerar usuarios**, y los buzones crecen sin tope dentro de `db.json`. |
| G-20 | 🔵 | `guaguapass/tools/make_icon.js` | Script auxiliar no invocado por `build.sh` (código muerto en el flujo de compilación). |

### 10.3 Lo que está bien en GuaguaPass

- Uso de `scrypt` con sal por usuario (`hashPass`, `server.js:149-151`) en lugar de SHA-256 simple.
- Comprobación de conflicto de asiento en el servidor (409) y *broadcast* inmediato a los suscriptores SSE: la semántica de concurrencia es correcta para Node (single-thread).
- Cancelación restringida al dueño de la venta (`server.js:462-470`).
- *Fallback* de SSE a polling de 1 s bien planteado conceptualmente.
- `db.json` con `rev` incremental permite descartar estados viejos.

### 10.4 Plan de corrección mínimo para GuaguaPass (ordenado)

1. **Sacar el keystore y los APK del repositorio** (`.gitignore`), rotar la clave, y mover la firma a CI con *secrets*.
2. **Verificación de actualizaciones**: (a) `FileProvider` en lugar de `file://`; (b) `sha256` **embebido en la app** por versión o, mejor, verificación de firma del APK descargado (`PackageManager.getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES`) comparando con la firma en ejecución; (c) HTTPS con *pinning* opcional.
3. **Cerrar el aprovisionamiento**: `GUAGUA_PROVISION=0` por defecto, clave por despliegue (nunca embebida), `/api/user` protegido, *rate-limit* y *PoW* o código de invitación.
4. **TLS obligatorio** (Caddy/nginx) y cabeceras de seguridad; `SECRET` persistente desde entorno.
5. **Minimizar datos**: `/api/state` debe devolver sólo `seat`, `user_id` y (como mucho) iniciales; teléfono/destino/precio sólo al dueño o a un rol supervisor.
6. **Endurecer el servidor**: `sseAlive` real, interrupción de SSE, límite de cuerpo con respuesta 413, escritura atómica (`write tmp` + `rename`), copia de seguridad, límites de correo.
7. **App**: `EncryptedSharedPreferences` (o Keystore) para el token; parser JSON real (`org.json` está disponible en Android); `RecyclerView`/actualización por asiento; strings en `strings.xml`; ciclo de vida correcto.

---

## 11. CI/CD y cadena de suministro

| ID | Severidad | Evidencia | Hallazgo |
|---|---|---|---|
| C-01 | 🟡 | `.github/workflows/build-apk.yml:29-36` | Workflow roto: sin submódulos ni compilación del core; `./gradlew assembleDebug`; artefacto en `app/build/...` (inexistente). |
| C-02 | 🔵 | `release.yml:51-58` | El keystore se escribe en `~/keystore.jks` desde un *secret* base64 y las contraseñas en `~/.gradle/gradle.properties`: correcto para GH Actions, pero no se limpia el archivo al final del job (queda en el runner efímero; aceptable). |
| C-03 | 🔵 | `release.yml:12-22` | El job `check` usa `::set-output` (deprecado) y siempre publica si el tag coincide; no valida que `versionName` coincida con el tag. |
| C-04 | 🔵 | `cache-core.yml:4-10` | `on.push.paths: 'jni/deltachat-core-rust'` nunca se dispara por cambios en el submódulo (Git solo cambia el *gitlink* de la raíz). |
| C-05 | 🟡 | `GuaguaPass-manual-apk.yml` + `build.sh` | La APK se firma con el keystore **versionado** y contraseña fija: el artefacto de CI es indistinguible de uno firmado por un atacante (G-01). |
| C-06 | 🔵 | `.github/workflows/*` | No hay análisis estático (CodeQL/Semgrep), ni `./gradlew lint`, ni tests unitarios en CI (`spotlessCheck` es lo único que valida código). |

---

## 12. Pruebas y mantenibilidad

| Métrica | Valor | Comentario |
|---|---:|---|
| Archivos Java | 446 | 439 app + 7 GuaguaPass |
| LOC Java | 72.831 | ~71.100 app + ~1.700 GuaguaPass |
| Tests unitarios (`src/test`) | **0** | No existe el directorio |
| Tests instrumentados | 5 archivos | `ForwardingTest`, `SharingTest`, `OnboardingTest` (online, requiere credenciales), benchmark de chats |
| `catch (Exception)` genéricos | 137 | Se pierde el diagnóstico |
| `printStackTrace()` | 69 | Debería ser `Log.e(TAG, …, e)` |
| `Log.*` | 655 | Incluye datos sensibles (S-02) |
| `new Thread(...)` | 21 | Fuera de executors/JobManager |
| Comentarios `TODO/FIXME/XXX/HACK` | 10 | Bajo |
| Formato | Spotless + google-java-format | Sólo `src/*/java`; **GuaguaPass queda fuera** |
| Strings traducibles | 16 idiomas en `res/values-*` | La app principal está bien internacionalizada; GuaguaPass no |

**Recomendaciones de mantenibilidad**

1. Añadir `src/test` con tests JVM puros para `Util`, `Prefs`, `MediaUtil`, `JsonUtils`, `BaseRpcTransport` (con un transporte falso), parsers de QR y `DcEventCenter`.
2. Reglas de Lint que fallen el build: `UnsafeProtectedBroadcastReceiver`, `ExportedContentProvider`, `JavascriptInterface`, `SetJavaScriptEnabled`, `HardcodedText`, `WakelockTimeout`.
3. Extraer `ConversationActivity`/`ConversationFragment` a controladores testeables.
4. Unificar el acceso al core: elegir JNI **o** RPC por área, documentarlo.
5. Añadir `spotless` para `guaguapass/src` y un job que valide `node --check server/server.js` (ya existe como paso opcional) y `javac -Xlint`.

---

## 13. Plan de acción priorizado

### P0 — inmediato (seguridad crítica)

1. **GuaguaPass: rotar la clave de firma** y purgar del historial `guaguapass/out/**` (keystore, APK, dex, class). Añadir `.gitignore`:
   ```gitignore
   guaguapass/out/
   build/
   .gradle/
   local.properties
   *.jks
   *.keystore
   ```
2. **Arreglar la actualización**: `FileProvider` + verificación de firma del APK descargado + URL fija HTTPS; desactivar el auto-update hasta que esté verificado.
3. **Cerrar `/api/auto-register` y `/api/user`** por defecto; clave de provisión por entorno, no en el APK.

### P1 — corto plazo (alto)

4. `ApplicationContext`: `catch (Throwable)` + `notifyAll()` + estado de fallo + timeout en `ensureInitialized()` (S-07).
5. `BaseRpcTransport`: timeouts, `catch (Throwable)`, completar futures, hilo daemon, back-off (S-03).
6. Redactar el crash handler y eliminar el correo fijo + `finalize()` (S-06).
7. Quitar credenciales TURN del log (S-02).
8. `WebxdcActivity`: validar host en `interceptRequest` y en la navegación (S-01).
9. `InstantOnboardingActivity`: no resolver QRs remotos de hosts arbitrarios sin confirmación (S-05).
10. GuaguaPass: minimizar datos en `/api/state`, TLS, rate-limit, límite de cuerpo, persistencia atómica (G-05…G-10).

### P2 — medio plazo (medio)

11. `DcHelper.sharedFiles` → `ConcurrentHashMap` con clave por cuenta + TTL; validar `canonicalPath` en `AttachmentsContentProvider` (S-08, B-01).
12. `PersistentBlobProvider`: ids únicos; documentar que la URI no es un provider real (B-02, B-03).
13. `DcEventCenter`: filtrar por cuenta y limpiar observadores (B-05).
14. `BootReceiver`/`PanicResponderListener`: `exported=false`/permiso de firma (S-09).
15. `file_provider_paths.xml`: eliminar `root-path /storage/` (S-10).
16. CI: arreglar `build-apk.yml`, añadir Lint + tests + CodeQL (C-01, C-06).
17. GuaguaPass: `RecyclerView`/diff de asientos, parser JSON real, ciclo de vida, i18n (G-13…G-17).

### P3 — largo plazo (deuda)

18. Partir `ConversationActivity`/`ConversationFragment`/`ConversationItem`.
19. Sustituir `util/guava` y `stickyheadergrid` por APIs modernas.
20. Cobertura de tests JVM ≥ 25 % en `util`/`connect`/`rpc`.
21. Notificaciones: privacidad por defecto `"contact"` y onboarding explícito (S-12).

---

## 14. Anexos

### 14.1 Comandos usados (reproducibles)

```bash
# inventario y métricas
find src/main/java -name '*.java' | wc -l
find src/main/java -name '*.java' | xargs wc -l | tail -1

# patrones de riesgo
grep -rn "addJavascriptInterface\|setAllowFileAccess\|loadUrl(" src/main/java
grep -rn "printStackTrace" src/main/java | wc -l
grep -rn "catch (Exception e)" src/main/java | wc -l
grep -rn "new Thread(" src/main/java | wc -l
grep -rn "Uri.fromFile\|getUriForFile" src/main/java

# componentes exportados del manifiesto
grep -n -B3 'android:exported="true"' src/main/AndroidManifest.xml

# GuaguaPass
grep -n "PROVISION_KEY\|SECRET\|hashPass\|verify(" guaguapass/server/server.js
grep -n "sha256\|downloadAndInstall\|Uri.fromFile" guaguapass/src/com/guaguapass/Updater.java
```

### 14.2 Glosario

- **Core**: librería Rust `deltachat-core-rust` (protocolo email, Autocrypt, SecureJoin). Aquí sólo se ve su fachada.
- **JNI**: puente Java↔C (`jni/dc_wrapper.c`) que expone `dc_*` como métodos de `DcContext`.
- **JSON-RPC**: API nueva del core, expuesta por `chat.delta.rpc.Rpc`.
- **WebXDC**: apps HTML empaquetadas (`.xdc`) que se ejecutan en un WebView aislado con `webview.js` e `InternalJSApi`.
- **Relay**: cuenta/transporte adicional que reenvía mensajes (multi-transporte).
- **SSE**: *Server-Sent Events*, canal HTTP unidireccional usado por GuaguaPass para *push*.
- **TURN/STUN**: servidores de relay y descubrimiento de IP pública para las llamadas WebRTC.

### 14.3 Cobertura del análisis

| Área | Cobertura |
|---|---|
| GuaguaPass (app, servidor, build, manifiesto, workflow) | **100 %** leído línea a línea |
| Núcleo de arranque y puente con el core | 100 % (`ApplicationContext`, `DcHelper`, `DcEventCenter`, `BaseRpcTransport`, `FFITransport`) |
| WebXDC / WebView | 100 % de `WebxdcActivity`, `WebViewActivity`, `WebxdcStoreActivity`, `FullMsgActivity` |
| Llamadas | `WebRTCClient` y `CallService` (ICE, SDP, logging) 100 %; `CallCoordinator`/`CallActivity` por estructura y puntos críticos |
| QR / onboarding / relay | Flujos críticos 100 %; resto por firmas |
| Resto de la app (≈330 archivos) | Estructura, firmas, patrones de riesgo y métricas |
| Core Rust | **0 %** (no está en el checkout) |

---

*Fin del informe. Los datos tabulares están en `inventario-archivos.csv` y `hallazgos.csv` (mismo directorio).*
