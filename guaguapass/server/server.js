#!/usr/bin/env node
/**
 * GuaguaPass — Servidor de sincronización estilo chatmail/arcanechat.
 * Sin dependencias externas: http + crypto nativos de Node.js.
 *
 *   GUAGUA_DOMAIN=guagua.example  GUAGUA_PROVISION=1  node server.js [puerto]
 *
 * METODO ARCANESCHAT (registro automatico tipo /secret-api/new-user):
 *   - La APK deriva usuario+contraseña ALEATORIOS de un seed local
 *     (como chatmail deriva <aleatorio>@dominio) y se los inventa una sola vez.
 *   - Al arrancar, llama POST /api/auto-register con {login,password,name}.
 *     Si la cuenta no existe, el servidor la CREA al vuelo (sin captcha ni
 *     administracion) y responde token JWT -> "autenticacion automatica".
 *   - Si ya existe, hace login normal con las mismas credenciales derivadas.
 *   - El endpoint esta protegido por cabecera X-Provision-Key (equivalente al
 *     endpoint secreto de chatmail). Con GUAGUA_PROVISION=0 se desactiva el
 *     registro publico y solo funcionan gestores creados via /api/user.
 *
 * SINCRONIZACION EN TIEMPO REAL (igual frecuencia que el polling de 1 s):
 *   - GET /api/events?date=&guagua=&hora=  -> Server-Sent Events: el servidor
 *     EMPUJA el estado del asiento en <=1 s tras cada venta/cancelacion.
 *   - GET /api/state ...                    -> snapshot JSON (fallback HTTP
 *     del polling clasico; tambien se envia como evento inicial por SSE).
 *   - Conflicto de asiento: el segundo en reservar recibe event "conflict"
 *     (equivalente al 409) y ve el cambio ajeno al instante.
 */
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.argv[2] || '3000', 10);
const DB_FILE = path.join(__dirname, 'db.json');
const PROVISION_KEY = process.env.GUAGUA_PROVISION_KEY || 'guagua-provision-key';
const PROVISION_OPEN = process.env.GUAGUA_PROVISION !== '0'; // por defecto abierto (estilo chatmail)
// Dominio de correo igual que la APK ArcaneChat: los usuarios se generan como
// <aleatorio>@arcanechat.me y el chat de correo usa este dominio.
const DOMAIN = process.env.GUAGUA_DOMAIN || 'arcanechat.me';

// ---------------- ACTUALIZACION DE LA APK (mismo servicio de correo) ----------------
// Igual que ArcaneChat distribuye arcanechat.apk desde su servidor de correo,
// aqui la APK GuaguaPass.apk vive en server/apk/ y SE REGENERA automaticamente
// cuando cambian los fuentes de la app (src/, res/, AndroidManifest.xml).
// La APK consulta GET /api/update?versionCode=N; si hay una compilacion nueva
// el servidor responde el manifiesto y GET /apk/GuaguaPass.apk la descarga.
const APK_DIR = path.join(__dirname, 'apk');
const APK_FILE = path.join(APK_DIR, 'GuaguaPass.apk');
const APP_SRC_DIR = path.resolve(__dirname, '..', 'src');
const APP_RES_DIR = path.resolve(__dirname, '..', 'res');
const APP_MANIFEST = path.resolve(__dirname, '..', 'AndroidManifest.xml');
const BUILD_SH = path.resolve(__dirname, '..', 'build.sh');
try { fs.mkdirSync(APK_DIR, { recursive: true }); } catch (e) {}

/** Version embebida leida del AndroidManifest (versionName="1.0"). */
function manifestVersion() {
  const m = String(process.env.GUAGUA_APP_VERSION || '').match(/^(\d+)\.(\d+)(?:\.(\d+))?$/);
  if (m) return [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3] || '0', 10)];
  try {
    const t = fs.readFileSync(APP_MANIFEST, 'utf8');
    const v = (t.match(/android:versionName="(\d+)\.(\d+)(?:\.(\d+))?"/) || []).slice(1);
    if (v[0]) return [parseInt(v[0], 10), parseInt(v[1], 10), parseInt(v[2] || '0', 10)];
  } catch (e) {}
  return [1, 0, 0];
}
function bumpVersion(built) {
  let [a, b, c] = built;
  if (!c) c++; else if (b < 99) b++; else { a++; b = 0; }
  return `${a}.${b}${c ? '.' + c : ''}`;
}
function cmpVer(x, y) {
  const a = String(x || '0').split('.').map(Number), b = String(y || '0').split('.').map(Number);
  for (let i = 0; i < 3; i++) { const d = (a[i] || 0) - (b[i] || 0); if (d) return d; }
  return 0;
}
/** Huella de los fuentes: cambia -> hay que recompilar la APK. */
function walkLatest(dir) {
  let latest = 0;
  try {
    for (const f of fs.readdirSync(dir)) {
      const fp = path.join(dir, f);
      const st = fs.statSync(fp);
      if (st.isDirectory()) latest = Math.max(latest, walkLatest(fp));
      else if (/\.(java|xml)$/.test(f)) latest = Math.max(latest, st.mtimeMs);
    }
  } catch (e) {}
  return latest;
}
function srcFingerprint() {
  return Math.round(Math.max(walkLatest(APP_SRC_DIR), walkLatest(APP_RES_DIR),
    safeMtime(APP_MANIFEST), safeMtime(BUILD_SH)));
}
function safeMtime(f) { try { return fs.statSync(f).mtimeMs; } catch (e) { return 0; } }
function readMeta() {
  try { return JSON.parse(fs.readFileSync(path.join(APK_DIR, 'meta.json'), 'utf8')); } catch (e) { return null; }
}
function writeMeta(m) { try { fs.writeFileSync(path.join(APK_DIR, 'meta.json'), JSON.stringify(m)); } catch (e) {} }

let building = false;
/** Regenera la APK ejecutando build.sh (si el SDK esta disponible en el servidor). */
function rebuildApk(force) {
  if (building) return;
  const fp = srcFingerprint();
  const meta = readMeta();
  if (!force && meta && meta.srcFp === fp && fs.existsSync(APK_FILE)) return; // ya al dia
  if (!fs.existsSync(BUILD_SH)) return;
  building = true;
  const { execFile } = require('child_process');
  execFile('/usr/bin/env', ['bash', BUILD_SH], { cwd: path.dirname(BUILD_SH), timeout: 600_000 },
    (err) => {
      building = false;
      if (err) { console.log('BUILD APK fallido (SDK no disponible?): ' + String(err.message).slice(0, 120)); return; }
      const base = manifestVersion();
      let built = meta && meta.version ? meta.version.split('.').map(Number) : null;
      let version = base.join('.');
      // Si los fuentes cambiaron sin tocar versionName, se auto-incrementa el parche
      // para que /api/update detecte la novedad (igual que los builds nocturnos de arcanechat.apk).
      if (built && cmpVer(base.join('.'), built.join('.')) <= 0 && meta && meta.srcFp !== fp) {
        version = bumpVersion(built);
      }
      let size = 0, sha = '';
      try {
        size = fs.statSync(APK_FILE).size;
        sha = crypto.createHash('sha256').update(fs.readFileSync(APK_FILE)).digest('hex');
      } catch (e) {}
      writeMeta({ version, base: base.join('.'), size, sha, builtAt: Date.now(), srcFp: fp });
      console.log(`APK regenerada: GuaguaPass.apk v${version} (${size} bytes)`);
    });
}
setInterval(() => { try { rebuildApk(false); } catch (e) {} }, 60_000).unref();
try { rebuildApk(false); } catch (e) {}

// ---------------- Config del negocio (editala aqui) ----------------
const CONFIG = {
  guaguas: ['GG-01 Terminal', 'GG-02 Viazo', 'GG-03 Camajuaní'],
  rutas: ['Santa Clara ↔ Camajuaní', 'Santa Clara ↔ Cifuentes', 'Santa Clara ↔ Placetas'],
  horarios: ['06:00', '08:00', '10:00', '12:00', '14:00', '16:00', '18:00'],
  dims: { cols: 4, rows: 12 }, // 4x12 = 48 asientos; numeracion columna-major
};

// ---------------- Persistencia simple en JSON ----------------
let db = { users: [], sales: [], nextUserId: 1, nextSaleId: 1, rev: 1 };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(DB_FILE, 'utf8'))); } catch (e) {}
let saveTimer = null;
function persist() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => fs.writeFileSync(DB_FILE, JSON.stringify(db)), 200);
}

function hashPass(pw, salt) {
  return crypto.scryptSync(pw, salt, 32).toString('hex');
}

// ---------------- Tokens (JWT HS256 casero) ----------------
const SECRET = process.env.GUAGUA_SECRET || crypto.randomBytes(16).toString('hex');
function b64url(s) { return Buffer.from(s).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_'); }
function sign(payload) {
  const h = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const p = b64url(JSON.stringify(payload));
  const sig = crypto.createHmac('sha256', SECRET).update(h + '.' + p).digest();
  return h + '.' + p + '.' + b64url(sig);
}
function verify(token) {
  try {
    const [h, p, s] = token.split('.');
    const expect = b64url(crypto.createHmac('sha256', SECRET).update(h + '.' + p).digest());
    if (s !== expect) return null;
    const json = JSON.parse(Buffer.from(p.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString());
    return json.exp > Date.now() / 1000 ? json : null;
  } catch (e) { return null; }
}

// ---------------- Helpers ----------------
function readBody(req) {
  return new Promise((res) => {
    let b = '';
    req.on('data', (c) => { b += c; if (b.length > 1e6) req.destroy(); });
    req.on('end', () => { try { res(JSON.parse(b || '{}')); } catch (e) { res({}); } });
  });
}
function send(res, code, obj) {
  const body = typeof obj === 'string' ? obj : JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}
function authed(req) {
  const h = req.headers.authorization || '';
  return verify(h.replace(/^Bearer\s+/i, ''));
}

// ================================================================
//  CAPA DE TIEMPO REAL (SSE): canales por (fecha|guagua|hora)
// ================================================================
const channels = new Map(); // key -> Set<res>
function chanKey(date, guagua, hora) { return `${date}|${guagua}|${hora}`; }

function sseSend(res, event, dataObj) {
  try {
    res.write(`event: ${event}\ndata: ${JSON.stringify(dataObj)}\n\n`);
  } catch (e) { /* cliente caido: se limpia en close */ }
}

function statePayload(date, guagua, hora) {
  const seats = db.sales
    .filter((s) => s.date === date && s.guagua === guagua && s.hora === hora)
    .map((s) => ({
      seat: s.seat, user_id: s.user_id, user_name: s.user_name, customer: s.customer,
      phone: s.phone, destino: s.destino, precio: s.precio, sale_time: s.saleTime,
    }))
    .sort((a, b) => a.seat - b.seat);
  return { rev: db.rev, seats };
}

/** Empuja a todos los suscriptores del canal (y a los duplicados por si acaso). */
function broadcast(date, guagua, hora, extraEvent, extraData) {
  const set = channels.get(chanKey(date, guagua, hora));
  if (!set || set.size === 0) return;
  const payload = statePayload(date, guagua, hora);
  for (const res of set) {
    if (extraEvent) sseSend(res, extraEvent, extraData);
    sseSend(res, 'state', payload);
  }
}

// latido para mantener NATs/firewalls despiertos
setInterval(() => {
  for (const set of channels.values())
    for (const res of set) { try { res.write(': ping\n\n'); } catch (e) {} }
}, 15000).unref();

// ================================================================
//  Registro interno de usuarios (manual y automatico comparten codigo)
// ================================================================
function createUser(username, password, name, auto) {
  if (!username || !password) return { err: 'username y password requeridos' };
  if (db.users.some((u) => u.username === username)) return { err: 'usuario existe' };
  const salt = crypto.randomBytes(8).toString('hex');
  // Buzon de correo estilo arcanechat: <local>@arcanechat.me
  const email = username.includes('@') ? username : `${username}@${DOMAIN}`;
  const user = {
    id: db.nextUserId++, username, email, name: name || username, salt,
    hash: hashPass(password, salt), auto: !!auto, created: Date.now(),
    mailbox: { inbox: [], sent: [] }, // chat de correo (mensajes internos)
  };
  db.users.push(user); persist();
  return { user };
}

// ---------------- Chat de correo (mensajeria interna estilo email) ----------------
function findUserByNameOrEmail(s) {
  s = String(s || '').toLowerCase();
  return db.users.find((u) => u.username === s || u.email === s || u.email === `${s}@${DOMAIN}`);
}

function mailSend(from, to, subject, body) {
  const dest = findUserByNameOrEmail(to);
  if (!dest) return { err: `destinatario no encontrado: ${to}` };
  const msg = {
    id: db.nextMailId = (db.nextMailId || 1), from: from.email || from.username,
    to: dest.email, subject: String(subject || '(sin asunto)').slice(0, 120),
    body: String(body || '').slice(0, 4000), ts: Date.now(), read: false,
  };
  db.nextMailId++;
  dest.mailbox.inbox.push(msg);
  (from.mailbox || (from.mailbox = { inbox: [], sent: [] })).sent.push(msg);
  persist();
  notifyUser(dest.id); // push SSE para el destinatario
  return { msg };
}

/** Empuja el evento "mail" al stream SSE del usuario si esta conectado. */
function notifyUser(uid) {
  const set = mailChannels.get(uid);
  if (!set) return;
  for (const res of set) { try { res.write('event: mail\ndata: {}\n\n'); } catch (e) {} }
}
const mailChannels = new Map(); // uid -> Set<res>

// ---------------- API ----------------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  // ---- AUTO-REGISTRO ESTILO ARCANECHAT/CHATMAIL (/secret-api/new-user) ----
  if (p === '/api/auto-register' && req.method === 'POST') {
    if (!PROVISION_OPEN) return send(res, 403, { error: 'provisionamiento cerrado' });
    const key = req.headers['x-provision-key'] || '';
    if (key !== PROVISION_KEY) return send(res, 403, { error: 'clave de provision invalida' });
    const b = await readBody(req);
    // Aceptamos tanto el login corto (mofaru) como la direccion completa
    // generada por la APK (mofaru@arcanechat.me), igual que ArcaneChat.
    let login = String(b.login || '').toLowerCase().replace(/[^a-z0-9._@-]/g, '').slice(0, 64);
    if (login.includes('@')) login = login.split('@')[0].slice(0, 32);
    else login = login.slice(0, 32);
    const password = String(b.password || '');
    const name = String(b.name || '').slice(0, 40);
    if (!login || password.length < 8) return send(res, 400, { error: 'login y password(>=8) requeridos' });
    let u = db.users.find((x) => x.username === login);
    let created = false;
    if (!u) {
      const r = createUser(login, password, name || ('Gestor-' + login.slice(-4)), true);
      if (r.err) return send(res, 409, { error: r.err });
      u = r.user; created = true;
      console.log(`AUTO-REGISTRO: ${u.email} (${created ? 'creado' : 'existia'})`);
    } else if (hashPass(password, u.salt) !== u.hash) {
      // existe con otra clave derivada: no secuestramos cuentas ajenas
      return send(res, 409, { error: 'usuario existe con otra clave' });
    }
    const token = sign({ uid: u.id, exp: Date.now() / 1000 + 86400 * 30 });
    return send(res, 200, { ok: true, created, token, id: u.id, username: u.username, email: u.email, name: u.name });
  }

  // ---- Config publica (incluye dominio y flags de provision para la APK) ----
  if (p === '/api/config') {
    const meta = readMeta();
    return send(res, 200, Object.assign({}, CONFIG, {
      domain: DOMAIN,
      server: process.env.GUAGUA_SERVER || 'https://arcanechat.me',
      provision_open: PROVISION_OPEN,
      apk_version: meta ? meta.version : null,
    }));
  }

  // ---- ACTUALIZACION DE LA APK (servida por el mismo servidor de correo) ----
  // GET /api/update?version=1.0  -> {update, latest, url, size, sha256}
  if (p === '/api/update' && req.method === 'GET') {
    rebuildApk(false); // si los fuentes cambiaron, se recompila al vuelo
    const meta = readMeta();
    if (!meta || !fs.existsSync(APK_FILE)) return send(res, 200, { update: false, error: 'sin apk compilada' });
    const cur = url.searchParams.get('version') || '0';
    return send(res, 200, {
      update: cmpVer(meta.version, cur) > 0,
      latest: meta.version,
      url: '/apk/GuaguaPass.apk',
      size: meta.size,
      sha256: meta.sha,
      builtAt: meta.builtAt,
    });
  }

  // Descarga directa de la APK generada (igual que arcanechat.me/.../arcanechat.apk)
  if ((p === '/apk/GuaguaPass.apk' || p === '/download' || p === '/GuaguaPass.apk') && req.method === 'GET') {
    if (!fs.existsSync(APK_FILE)) return send(res, 404, { error: 'apk no compilada aun' });
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': fs.statSync(APK_FILE).size,
      'Content-Disposition': 'attachment; filename="GuaguaPass.apk"',
    });
    fs.createReadStream(APK_FILE).pipe(res);
    return;
  }

  // Registro manual de gestores (administracion local / curl)
  if (p === '/api/user' && req.method === 'POST') {
    const b = await readBody(req);
    const r = createUser(b.username, b.password, b.name, false);
    if (r.err) return send(res, r.err === 'usuario existe' ? 409 : 400, { error: r.err });
    return send(res, 200, { ok: true, id: r.user.id });
  }

  if (p === '/api/login' && req.method === 'POST') {
    const b = await readBody(req);
    // Acepta usuario corto (mofaru) o correo completo (mofaru@arcanechat.me)
    let uname = String(b.username || '').toLowerCase();
    if (uname.includes('@')) uname = uname.split('@')[0];
    const u = db.users.find((x) => x.username === uname);
    if (!u || hashPass(String(b.password || ''), u.salt) !== u.hash) return send(res, 401, { error: 'credenciales invalidas' });
    const token = sign({ uid: u.id, exp: Date.now() / 1000 + 86400 * 30 });
    return send(res, 200, { token, id: u.id, username: u.username, email: u.email, name: u.name });
  }

  // Todo lo demas requiere token
  const me = authed(req);
  if (!me) return send(res, 401, { error: 'no autenticado' });
  const user = db.users.find((x) => x.id === me.uid);
  if (!user) return send(res, 401, { error: 'usuario eliminado' });

  const qDate = url.searchParams.get('date') || '';
  const qGuagua = url.searchParams.get('guagua') || '';
  const qHora = url.searchParams.get('hora') || '';

  // Snapshot JSON (fallback del polling clasico de 1 s)
  if (p === '/api/state' && req.method === 'GET') {
    return send(res, 200, statePayload(qDate, qGuagua, qHora));
  }

  // ---- STREAM TIEMPO REAL (SSE): push inmediato, misma semantica de 1 s ----
  if (p === '/api/events' && req.method === 'GET') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    const key = chanKey(qDate, qGuagua, qHora);
    let set = channels.get(key);
    if (!set) { set = new Set(); channels.set(key, set); }
    set.add(res);
    sseSend(res, 'state', statePayload(qDate, qGuagua, qHora)); // estado inicial inmediato
    // Canal de notificaciones de correo de este usuario (chat de correo)
    let mset = mailChannels.get(user.id);
    if (!mset) { mset = new Set(); mailChannels.set(user.id, mset); }
    mset.add(res);
    req.on('close', () => {
      set.delete(res); if (set.size === 0) channels.delete(key);
      mset.delete(res); if (mset.size === 0) mailChannels.delete(user.id);
    });
    return;
  }

  // ---- CHAT DE CORREO (mensajeria interna estilo email, dominio arcanechat.me) ----
  if (p === '/api/mail/send' && req.method === 'POST') {
    const b = await readBody(req);
    const r = mailSend(user, b.to, b.subject, b.body);
    if (r.err) return send(res, 404, { error: r.err });
    return send(res, 200, { ok: true, id: r.msg.id, to: r.msg.to });
  }

  if (p === '/api/mail/list' && req.method === 'GET') {
    if (!user.mailbox) user.mailbox = { inbox: [], sent: [] };
    const unread = user.mailbox.inbox.filter((m) => !m.read).length;
    return send(res, 200, {
      from: user.email,
      inbox: user.mailbox.inbox.slice(-100),
      sent: user.mailbox.sent.slice(-100),
      unread,
    });
  }

  if (p === '/api/mail/read' && req.method === 'POST') {
    const b = await readBody(req);
    if (!user.mailbox) user.mailbox = { inbox: [], sent: [] };
    for (const m of user.mailbox.inbox) if (b.all || m.id === b.id) m.read = true;
    persist();
    return send(res, 200, { ok: true });
  }

  if (p === '/api/sale' && req.method === 'POST') {
    const b = await readBody(req);
    const seat = parseInt(b.seat, 10);
    if (!(seat > 0 && seat <= CONFIG.dims.cols * CONFIG.dims.rows)) return send(res, 400, { error: 'asiento invalido' });
    if (!b.date || !b.guagua || !b.hora) return send(res, 400, { error: 'falta fecha/guagua/hora' });
    if (!b.customer) return send(res, 400, { error: 'falta nombre del pasajero' });
    const clash = db.sales.find((s) => s.date === b.date && s.guagua === b.guagua && s.hora === b.hora && s.seat === seat);
    if (clash) {
      // conflicto: empuja el estado actualizado al canal para que todos lo vean ya
      broadcast(b.date, b.guagua, b.hora);
      return send(res, 409, { error: 'asiento ya vendido', by: clash.user_name });
    }
    const sale = {
      id: db.nextSaleId++, seat, date: b.date, guagua: b.guagua, hora: b.hora,
      user_id: user.id, user_name: user.name,
      customer: String(b.customer).slice(0, 80), phone: String(b.phone || '').slice(0, 30),
      destino: String(b.destino || '').slice(0, 80), precio: String(b.precio || '0').slice(0, 12),
      saleTime: Date.now(),
    };
    db.sales.push(sale); db.rev++; persist();
    broadcast(sale.date, sale.guagua, sale.hora);
    console.log(`VENTA #${sale.id}: asiento ${seat} ${sale.date}|${sale.guagua}|${sale.hora} -> ${sale.customer} por ${sale.user_name}`);
    return send(res, 200, { ok: true, id: sale.id, rev: db.rev });
  }

  if (p === '/api/cancel' && req.method === 'POST') {
    const b = await readBody(req);
    const idx = db.sales.findIndex((s) => s.id === b.id && s.user_id === user.id);
    if (idx < 0) return send(res, 403, { error: 'solo puedes cancelar tus propias ventas' });
    const s = db.sales[idx];
    db.sales.splice(idx, 1); db.rev++; persist();
    broadcast(s.date, s.guagua, s.hora);
    return send(res, 200, { ok: true });
  }

  send(res, 404, { error: 'no encontrado' });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`GuaguaPass server en http://0.0.0.0:${PORT}`);
  console.log(`Auto-registro (metodo arcanechat): ${PROVISION_OPEN ? 'ABIERTO' : 'CERRADO'} · clave: ${PROVISION_KEY}`);
  console.log("Manual: curl -X POST http://localhost:%d/api/user -d '{\"username\":\"maria\",\"password\":\"secreta\",\"name\":\"María\"}'", PORT);
});
