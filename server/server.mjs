import { requestCompletion } from './provider.mjs';
import http from 'node:http';
import { DEFAULT_MODEL } from './prompt.mjs';
import { randomUUID, randomBytes, createHash, createSign } from 'node:crypto';
import { existsSync, readFileSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const hash = value => createHash('sha256').update(value).digest('hex');
const secret = () => randomBytes(32).toString('base64url');
const MAX_GENERATIONS = 200;
const MAX_OUTPUT = 120000;

export function createChatServer(options = {}) {
  const config = {
    apiKey: process.env.OPENROUTER_API_KEY || '',
    backupKey: process.env.OPENROUTER_API_KEY_BACKUP || '',
    model: process.env.OPENROUTER_MODEL || DEFAULT_MODEL,
    ttl: Number(process.env.ACCESS_TTL_SECONDS || 60) * 1000,
    dataFile: resolve('data/state.json'),
    fetch: globalThis.fetch,
    ...options,
  };
  let state = { accounts: {}, sessions: {}, purchaseOwners: {} };
  if (config.dataFile && existsSync(config.dataFile)) state = JSON.parse(readFileSync(config.dataFile, 'utf8'));
  state.purchaseOwners ??= {};
  state.reservations ??= {};
  for (const { device } of Object.values(state.reservations)) {
    if (state.accounts[device]) state.accounts[device].remaining++;
  }
  state.reservations = {};
  const generations = new Map();
  const attempts = new Map();
  const metrics = { refreshes: 0, generations: 0 };
  function save() {
    if (!config.dataFile) return;
    mkdirSync(dirname(config.dataFile), { recursive: true });
    writeFileSync(config.dataFile + '.tmp', JSON.stringify(state), { mode: 0o600 });
    renameSync(config.dataFile + '.tmp', config.dataFile);
  }
  const fail = (status, message) => Object.assign(new Error(message), { status });
  function account(device) {
    return state.accounts[device] ??= { remaining: 3, subscriptionUntil: 0 };
  }
  function active(a) { return a.subscriptionUntil > Date.now(); }
  function publicAccount(device) {
    const a = account(device);
    return { remaining: a.remaining, subscribed: active(a) };
  }
  function issue(device, oldRefresh) {
    if (oldRefresh) delete state.sessions[oldRefresh];
    const access = secret(), refresh = secret();
    state.sessions[hash(refresh)] = { device, accessHash: hash(access), expiresAt: Date.now() + config.ttl, refreshUntil: Date.now() + 30 * 86400000 };
    save();
    return { access_token: access, refresh_token: refresh, expires_in: config.ttl / 1000, ...publicAccount(device) };
  }
  function authenticate(req) {
    const token = req.headers.authorization?.replace(/^Bearer /, '');
    const session = token && Object.values(state.sessions).find(s => s.accessHash === hash(token));
    if (!session || session.expiresAt <= Date.now()) throw fail(401, 'Токен истёк');
    return session;
  }
  async function body(req) {
    let data = '';
    for await (const chunk of req) {
      data += chunk;
      if (data.length > 100000) throw fail(413, 'Слишком длинный запрос');
    }
    try { return data ? JSON.parse(data) : {}; } catch { throw fail(400, 'Некорректный JSON'); }
  }
  function json(res, status, value) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(value));
  }
  function add(g, type, payload = {}) {
    if (g.done) return;
    if (['done', 'cancelled', 'failed'].includes(type)) {
      const key = `${g.device}:${g.id}`;
      if (state.reservations[key]) {
        if (type !== 'done') account(g.device).remaining++;
        delete state.reservations[key];
        save();
      }
      payload = { ...payload, ...publicAccount(g.device) };
    }
    const event = { id: g.events.length + 1, type, ...payload };
    g.events.push(event);
    if (['done', 'cancelled', 'failed'].includes(type)) g.done = true;
    for (const send of [...g.listeners]) send(event);
  }
  async function generate(g, messages) {
    try {
      if (!config.apiKey) throw new Error('На сервере не настроен ключ OpenRouter');
      {
        const timeout = setTimeout(() => g.controller.abort(new Error('Истекло время ожидания модели')), 180000);
        try {
          const response = await requestCompletion({ apiKey: config.apiKey, backupKey: config.backupKey,
            model: config.model, messages, signal: g.controller.signal, fetcher: config.fetch });
          if (!response.ok) throw new Error(response.status === 429 ? 'Бесплатная модель временно недоступна или достигнут лимит запросов. Попробуйте позже.' : `Сервис AI недоступен (${response.status})`);
          let buffer = '', outputLength = 0, completed = false;
          const decoder = new TextDecoder();
          for await (const chunk of response.body) {
            buffer += decoder.decode(chunk, { stream: true }).replace(/\r/g, '');
            let boundary;
            while ((boundary = buffer.indexOf('\n\n')) >= 0) {
              const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
              const data = block.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
              if (!data) continue;
              if (data === '[DONE]') { completed = true; continue; }
              const value = JSON.parse(data);
              if (value.error) throw new Error('Сервис AI прервал генерацию. Полученный текст сохранён — нажмите «Повторить». Сообщение не списано.');
              if (value.model) g.model = value.model;
              const content = value.choices?.[0]?.delta?.content;
              if (content) {
                outputLength += content.length;
                if (outputLength > MAX_OUTPUT) throw new Error('Ответ превысил допустимую длину');
                add(g, 'delta', { text: content });
              }
              if (value.choices?.[0]?.finish_reason === 'error') throw new Error('Сервис AI прервал генерацию. Сообщение не списано.');
              if (value.choices?.[0]?.finish_reason) completed = true;
            }
          }
          if (!completed || outputLength === 0) throw new Error('Сервис AI не завершил ответ. Сообщение не списано.');
        } finally { clearTimeout(timeout); }
      }
      add(g, 'done');
    } catch (error) {
      if (!g.done) add(g, 'failed', { message: error.message || 'Ошибка генерации' });
    }
  }
  async function verifyPlay(token, device) {
    const file = process.env.GOOGLE_SERVICE_ACCOUNT_FILE;
    if (!file) throw fail(503, 'Проверка Google Play не настроена на сервере');
    if (typeof token !== 'string' || token.length > 4096) throw fail(400, 'Некорректная покупка');
    const key = JSON.parse(readFileSync(file, 'utf8'));
    const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
    const now = Math.floor(Date.now() / 1000);
    const unsigned = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({ iss: key.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })}`;
    const assertion = unsigned + '.' + createSign('RSA-SHA256').update(unsigned).sign(key.private_key, 'base64url');
    const oauth = await config.fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }), signal: AbortSignal.timeout(15000) });
    if (!oauth.ok) throw fail(502, 'Не удалось подключиться к Google Play');
    const { access_token } = await oauth.json();
    const pkg = process.env.ANDROID_PACKAGE || 'dev.test.aichat';
    const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(pkg)}`;
    const response = await config.fetch(`${base}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`, { headers: { Authorization: `Bearer ${access_token}` }, signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw fail(400, 'Google Play не подтвердил покупку');
    const purchase = await response.json();
    const allowed = (process.env.PLAY_PRODUCTS || 'chat_weekly,chat_monthly').split(',');
    const items = (purchase.lineItems || []).filter(i => allowed.includes(i.productId));
    const expiry = Math.max(0, ...items.map(i => Date.parse(i.expiryTime) || 0));
    const valid = ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'].includes(purchase.subscriptionState) && expiry > Date.now();
    if (!valid) {
      account(device).subscriptionUntil = 0;
      account(device).purchaseToken = null;
      save();
      throw fail(400, 'Активная подписка не найдена');
    }
    // Restore may transfer a verified Google Play receipt to a new installation.
    const receiptHash = hash(token);
    const previous = state.purchaseOwners[receiptHash];
    if (previous && previous !== device) { account(previous).subscriptionUntil = 0; account(previous).purchaseToken = null; }
    state.purchaseOwners[receiptHash] = device;
    account(device).purchaseToken = token;
    account(device).lastVerifiedAt = Date.now();
    account(device).subscriptionUntil = expiry;
    save();
    return publicAccount(device);
  }
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const path = url.pathname;
      if (path === '/health') return json(res, 200, { ok: true });
      if (req.method === 'POST' && path === '/auth/device') {
        const key = req.socket.remoteAddress || 'unknown';
        const recent = (attempts.get(key) || []).filter(t => t > Date.now() - 60000);
        if (recent.length >= 30) throw fail(429, 'Слишком много попыток входа');
        recent.push(Date.now()); attempts.set(key, recent);
        const b = await body(req);
        if (typeof b.device_id !== 'string' || !/^[a-zA-Z0-9-]{16,80}$/.test(b.device_id)) throw fail(400, 'Некорректный device_id');
        account(b.device_id);
        return json(res, 200, issue(b.device_id));
      }
      if (req.method === 'POST' && path === '/auth/refresh') {
        const b = await body(req), digest = typeof b.refresh_token === 'string' ? hash(b.refresh_token) : '';
        const old = state.sessions[digest];
        if (!old || old.refreshUntil < Date.now()) throw fail(401, 'Сессия истекла');
        metrics.refreshes++;
        return json(res, 200, issue(old.device, digest));
      }
      const session = authenticate(req), device = session.device;
      if (req.method === 'GET' && path === '/account') {
        const a = account(device);
        if (a.purchaseToken && (!a.lastVerifiedAt || a.lastVerifiedAt < Date.now() - 60000)) {
          try { await verifyPlay(a.purchaseToken, device); } catch (e) { if (e.status !== 400) throw e; }
        }
        return json(res, 200, publicAccount(device));
      }
      if (req.method === 'POST' && path === '/billing/verify') return json(res, 200, await verifyPlay((await body(req)).purchase_token, device));
      if (req.method === 'POST' && path === '/generations') {
        const b = await body(req);
        if (typeof b.request_id !== 'string' || !/^[a-zA-Z0-9-]{16,80}$/.test(b.request_id)) throw fail(400, 'Нужен request_id');
        const id = `${device}:${b.request_id}`;
        const existing = generations.get(id);
        if (existing) return json(res, 200, { generation_id: existing.id, ...publicAccount(device) });
        if (!Array.isArray(b.messages) || !b.messages.length || b.messages.length > 100 || b.messages.some(m => !['user', 'assistant'].includes(m.role) || typeof m.content !== 'string' || !m.content.trim() || m.content.length > 15000) || b.messages.at(-1).role !== 'user') throw fail(400, 'Некорректные сообщения');
        if (!config.apiKey) throw fail(503, 'На сервере не настроен ключ OpenRouter');
        const a = account(device);
        if (!active(a) && a.remaining <= 0) throw fail(402, 'Бесплатные сообщения закончились');
        if ([...generations.values()].filter(g => g.device === device && !g.done).length >= 2) throw fail(429, 'Сначала завершите текущий ответ');
        if (generations.size >= MAX_GENERATIONS) {
          const expired = [...generations.entries()].find(([, g]) => g.done);
          if (expired) generations.delete(expired[0]); else throw fail(503, 'Сервер занят');
        }
        if (!active(a)) { a.remaining--; state.reservations[id] = { device }; }
        const g = { id: b.request_id, device, events: [], listeners: new Set(), controller: new AbortController(), done: false, createdAt: Date.now(), messages: b.messages, retryCount: 0 };
        generations.set(id, g); metrics.generations++; save();
        json(res, 200, { generation_id: g.id, ...publicAccount(device) });
        void generate(g, b.messages);
        return;
      }
      const match = path.match(/^\/generations\/([a-zA-Z0-9-]+)\/(events|cancel|retry)$/);
      if (match) {
        const g = generations.get(`${device}:${match[1]}`);
        if (!g) throw fail(404, 'Ответ больше недоступен на сервере. Сохранённый текст остаётся в истории.');
        if (match[2] === 'retry' && req.method === 'POST') {
          const b = await body(req);
          if (typeof b.request_id !== 'string' || !/^[a-zA-Z0-9-]{16,80}$/.test(b.request_id)) throw fail(400, 'Нужен request_id');
          const id = `${device}:${b.request_id}`;
          const existing = generations.get(id);
          if (existing) return json(res, 200, { generation_id: existing.id, ...publicAccount(device) });
          if (!g.done || g.events.at(-1)?.type !== 'failed') throw fail(409, 'Этот ответ не требует новой генерации');
          if (g.retryCount >= 3) throw fail(429, 'Лимит повторных попыток исчерпан. Отправьте новое сообщение позже.');
          if (g.retryChild) throw fail(409, 'Повторный ответ уже создан');
          const a = account(device);
          if (!active(a) && a.remaining <= 0) throw fail(402, 'Бесплатные сообщения закончились');
          if ([...generations.values()].filter(g => g.device === device && !g.done).length >= 2) throw fail(429, 'Сначала завершите текущий ответ');
          if (!active(a)) { a.remaining--; state.reservations[id] = { device }; }
          const retry = { id: b.request_id, device, events: [], listeners: new Set(), controller: new AbortController(), done: false, createdAt: Date.now(), messages: g.messages, retryCount: g.retryCount + 1 };
          g.retryChild = id;
          generations.set(id, retry); metrics.generations++;
          json(res, 200, { generation_id: retry.id, ...publicAccount(device) });
          void generate(retry, retry.messages);
          return;
        }
        if (match[2] === 'cancel' && req.method === 'POST') {
          add(g, 'cancelled'); g.controller.abort(); return json(res, 200, { ok: true, ...publicAccount(device) });
        }
        if (match[2] === 'events' && req.method === 'GET') {
          const after = Number(url.searchParams.get('after') || 0);
          if (!Number.isInteger(after) || after < 0 || after > g.events.length) throw fail(400, 'Некорректный курсор');
          res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
          res.flushHeaders();
          let closed = false;
          const transient = (type, message) => {
            if (closed) return;
            res.write(`event: ${type}\ndata: ${JSON.stringify({ message })}\n\n`); finish();
          };
          const send = event => {
            if (closed) return;
            if (session.expiresAt <= Date.now()) return transient('auth_expired', 'Обновите токен');
            res.write(`id: ${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
            if (['done', 'cancelled', 'failed'].includes(event.type)) finish();
          };
          const heartbeat = setInterval(() => {
            if (session.expiresAt <= Date.now()) transient('auth_expired', 'Обновите токен');
            else res.write(': heartbeat\n\n');
          }, 5000);
          function finish() { closed = true; clearInterval(heartbeat); g.listeners.delete(send); res.end(); }
          res.on('close', () => { closed = true; clearInterval(heartbeat); g.listeners.delete(send); });
          g.listeners.add(send);
          for (const event of g.events) if (event.id > after) send(event);
          if (g.done && !closed) finish();
          return;
        }
      }
      throw fail(404, 'Не найдено');
    } catch (error) {
      if (!res.headersSent) json(res, error.status || 500, { message: error.status ? error.message : 'Внутренняя ошибка сервера' });
      else res.end();
    }
  });
  const cleanup = setInterval(() => {
    for (const [id, g] of generations) if (g.createdAt < Date.now() - 3600000) { g.controller.abort(); generations.delete(id); }
    for (const [key, s] of Object.entries(state.sessions)) if (s.refreshUntil < Date.now()) delete state.sessions[key];
    for (const [ip, times] of attempts) if (times.at(-1) < Date.now() - 60000) attempts.delete(ip);
  }, 60000).unref();
  server.on('close', () => { clearInterval(cleanup); for (const g of generations.values()) g.controller.abort(); });
  save();
  return { server, metrics, state, generations };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { server } = createChatServer();
  server.listen(Number(process.env.PORT || 8787), process.env.HOST || '127.0.0.1', () => {
    console.log(`AI Chat backend: http://${process.env.HOST || '127.0.0.1'}:${process.env.PORT || 8787} (${process.env.OPENROUTER_API_KEY ? 'OpenRouter configured' : 'OpenRouter key required'})`);
  });
}
