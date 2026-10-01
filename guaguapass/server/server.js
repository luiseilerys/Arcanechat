#!/usr/bin/env node
/**
 * GuaguaPass — Servidor de sincronizacion en tiempo real.
 * Sin dependencias externas: http + crypto nativos de Node.js.
 *
 *   node server.js [puerto]
 *
 * Cada gestor se registra con POST /api/user (una vez, desde esta consola o curl),
 * inicia sesion con POST /api/login y las ventas viajan por POST /api/sale.
 * La rejilla se sincroniza con GET /api/state cada 1 segundo (la APK hace polling).
 * El asiento (fecha+hora+guagua+numero) es unico a nivel de BD: si dos gestores
 * lo reservan a la vez, uno recibe HTTP 409 y ve el cambio en <=1 s.
 */
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.argv[2] || '3000', 10);
const DB_FILE = path.join(__dirname, 'db.json');

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

// ---------------- API ----------------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  // Registro de gestores (administracion local / curl)
  if (p === '/api/user' && req.method === 'POST') {
    const b = await readBody(req);
    if (!b.username || !b.password) return send(res, 400, { error: 'username y password requeridos' });
    if (db.users.some((u) => u.username === b.username)) return send(res, 409, { error: 'usuario existe' });
    const salt = crypto.randomBytes(8).toString('hex');
    const user = { id: db.nextUserId++, username: b.username, name: b.name || b.username, salt, hash: hashPass(b.password, salt) };
    db.users.push(user); persist();
    return send(res, 200, { ok: true, id: user.id });
  }

  if (p === '/api/login' && req.method === 'POST') {
    const b = await readBody(req);
    const u = db.users.find((x) => x.username === b.username);
    if (!u || hashPass(String(b.password || ''), u.salt) !== u.hash) return send(res, 401, { error: 'credenciales invalidas' });
    const token = sign({ uid: u.id, exp: Date.now() / 1000 + 86400 * 30 });
    return send(res, 200, { token, id: u.id, username: u.username, name: u.name });
  }

  // Todo lo demas requiere token
  const me = authed(req);
  if (!me) return send(res, 401, { error: 'no autenticado' });
  const user = db.users.find((x) => x.id === me.uid);
  if (!user) return send(res, 401, { error: 'usuario eliminado' });

  if (p === '/api/config') return send(res, 200, CONFIG);

  if (p === '/api/state' && req.method === 'GET') {
    const date = url.searchParams.get('date') || '';
    const guagua = url.searchParams.get('guagua') || '';
    const hora = url.searchParams.get('hora') || '';
    const seats = db.sales
      .filter((s) => s.date === date && s.guagua === guagua && s.hora === hora)
      .map((s) => ({
        seat: s.seat, user_id: s.user_id, user_name: s.user_name, customer: s.customer,
        phone: s.phone, destino: s.destino, precio: s.precio, sale_time: s.saleTime,
      }))
      .sort((a, b) => a.seat - b.seat);
    return send(res, 200, { rev: db.rev, seats });
  }

  if (p === '/api/sale' && req.method === 'POST') {
    const b = await readBody(req);
    const seat = parseInt(b.seat, 10);
    if (!(seat > 0 && seat <= CONFIG.dims.cols * CONFIG.dims.rows)) return send(res, 400, { error: 'asiento invalido' });
    if (!b.date || !b.guagua || !b.hora) return send(res, 400, { error: 'falta fecha/guagua/hora' });
    if (!b.customer) return send(res, 400, { error: 'falta nombre del pasajero' });
    const clash = db.sales.find((s) => s.date === b.date && s.guagua === b.guagua && s.hora === b.hora && s.seat === seat);
    if (clash) return send(res, 409, { error: 'asiento ya vendido', by: clash.user_name });
    const sale = {
      id: db.nextSaleId++, seat, date: b.date, guagua: b.guagua, hora: b.hora,
      user_id: user.id, user_name: user.name,
      customer: String(b.customer).slice(0, 80), phone: String(b.phone || '').slice(0, 30),
      destino: String(b.destino || '').slice(0, 80), precio: String(b.precio || '0').slice(0, 12),
      saleTime: Date.now(),
    };
    db.sales.push(sale); db.rev++; persist();
    console.log(`VENTA #${sale.id}: asiento ${seat} ${sale.date}|${sale.guagua}|${sale.hora} -> ${sale.customer} por ${sale.user_name}`);
    return send(res, 200, { ok: true, id: sale.id, rev: db.rev });
  }

  if (p === '/api/cancel' && req.method === 'POST') {
    const b = await readBody(req);
    const idx = db.sales.findIndex((s) => s.id === b.id && s.user_id === user.id);
    if (idx < 0) return send(res, 403, { error: 'solo puedes cancelar tus propias ventas' });
    db.sales.splice(idx, 1); db.rev++; persist();
    return send(res, 200, { ok: true });
  }

  send(res, 404, { error: 'no encontrado' });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`GuaguaPass server en http://0.0.0.0:${PORT}`);
  console.log("Crea gestores con: curl -X POST http://localhost:%d/api/user -d '{\"username\":\"maria\",\"password\":\"secreta\",\"name\":\"María\"}'", PORT);
});
