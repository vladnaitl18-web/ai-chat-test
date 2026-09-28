import { requestCompletion } from '../../server/provider.mjs';
import { DurableObject } from 'cloudflare:workers';
import { createHash, randomBytes, createSign } from 'node:crypto';
import { DEFAULT_MODEL } from '../../server/prompt.mjs';

type Body = Record<string, unknown>;
type Message = { role: 'user' | 'assistant'; content: string };
type Account = { remaining: number; subscriptionUntil: number; purchaseToken: string | null; receiptHash: string | null; verifiedAt: number };
type Session = { accessHash: string; refreshHash: string; expiresAt: number; refreshUntil: number };
type Generation = { id: string; messages: string; retries: number; retryChild: string | null; lastEvent: number; terminal: string | null; createdAt: number; reserved: number };
type Event = { id: number; type: string; text?: string; message?: string; remaining?: number; subscribed?: boolean };
type Listener = { send: (event: Event) => void };
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const validId = (value: unknown): value is string => typeof value === 'string' && /^[a-zA-Z0-9-]{16,80}$/.test(value);
const fail = (status: number, message: string) => Object.assign(new Error(message), { status });
const response = (value: unknown, status = 200) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } });
function errorResponse(error: unknown) {
  const status = error instanceof Error && 'status' in error && typeof error.status === 'number' ? error.status : 500;
  if (status === 500) console.error(JSON.stringify({ event: 'request_failed', name: error instanceof Error ? error.name : 'unknown' }));
  return response({ message: status === 500 ? 'Внутренняя ошибка сервера' : (error as Error).message }, status);
}
async function readBody(request: Request): Promise<Body> {
  if (!request.body) return {};
  const reader = request.body.getReader();
  const decoder = new TextDecoder();
  let size = 0, data = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 100000) { await reader.cancel(); throw fail(413, 'Слишком длинный запрос'); }
    data += decoder.decode(value, { stream: true });
  }
  try {
    const result = JSON.parse(data + decoder.decode());
    if (!result || Array.isArray(result) || typeof result !== 'object') throw new Error();
    return result;
  } catch { throw fail(400, 'Некорректный JSON'); }
}
function deviceFromToken(token: string): string {
  const parts = token.split('.');
  if (parts.length !== 3 || parts[0] !== 'v1' || !validId(parts[1]) || !/^[a-f0-9]{64}$/.test(parts[2])) throw fail(401, 'Сессия истекла');
  return parts[1];
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (url.pathname === '/health') return response({ ok: true, deployment: 'cloudflare' });
      const body = request.method === 'POST' ? await readBody(request) : {};
      const token = request.headers.get('Authorization')?.replace(/^Bearer /, '') || '';
      let device: string;
      if (url.pathname === '/auth/device' && request.method === 'POST') {
        const { success } = await env.AUTH_LIMIT.limit({ key: request.headers.get('CF-Connecting-IP') || 'unknown' });
        if (!success) throw fail(429, 'Слишком много попыток входа');
        if (!validId(body.device_id)) throw fail(400, 'Некорректный device_id');
        device = await env.DEVICES.getByName(body.device_id).resolveDevice(body.device_id, body.legacy_refresh_token);
      } else device = deviceFromToken(url.pathname === '/auth/refresh' ? String(body.refresh_token || '') : token);
      return await env.DEVICES.getByName(device).handle(device, url.pathname, url.search, request.method, token, body);
    } catch (error) { return errorResponse(error); }
  },
} satisfies ExportedHandler<Env>;

/** A separate persistent coordinator for each installation. No shared mutable user state in the Worker. */
export class DeviceChat extends DurableObject<Env> {
  private active = new Map<string, AbortController>();
  private listeners = new Map<string, Set<Listener>>();
  private lastCleanup = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS account (id INTEGER PRIMARY KEY CHECK(id=1), remaining INTEGER NOT NULL, subscriptionUntil INTEGER NOT NULL DEFAULT 0, purchaseToken TEXT, receiptHash TEXT, verifiedAt INTEGER NOT NULL DEFAULT 0);
      INSERT OR IGNORE INTO account (id,remaining) VALUES (1,3);
      CREATE TABLE IF NOT EXISTS sessions (refreshHash TEXT PRIMARY KEY, accessHash TEXT UNIQUE NOT NULL, expiresAt INTEGER NOT NULL, refreshUntil INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS generations (id TEXT PRIMARY KEY, messages TEXT NOT NULL, retries INTEGER NOT NULL DEFAULT 0, retryChild TEXT, lastEvent INTEGER NOT NULL DEFAULT 0, terminal TEXT, createdAt INTEGER NOT NULL, reserved INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS events (generationId TEXT NOT NULL, seq INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(generationId,seq));
      CREATE TABLE IF NOT EXISTS device_binding (id INTEGER PRIMARY KEY CHECK(id=1), canonical TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS migration_owner (id INTEGER PRIMARY KEY CHECK(id=1), stable TEXT NOT NULL);
    `);
    // An interrupted instance can replay persisted text and allow an explicit retry.
    for (const row of this.ctx.storage.sql.exec<{ id: string }>('SELECT id FROM generations WHERE terminal IS NULL')) {
      this.append(row.id, 'failed', { message: 'Сервер перезапустился. Полученный текст сохранён — нажмите «Повторить».' });
    }
  }

  private one<T extends Record<string, SqlStorageValue>>(sql: string, ...args: SqlStorageValue[]): T | undefined {
    return this.ctx.storage.sql.exec<T>(sql, ...args).toArray()[0];
  }
  private account(): Account { return this.one<Account>('SELECT * FROM account WHERE id=1')!; }
  private publicAccount() { const a = this.account(); return { remaining: a.remaining, subscribed: a.subscriptionUntil > Date.now() }; }
  private generation(id: string) { return this.one<Generation>('SELECT * FROM generations WHERE id=?', id); }
  async resolveDevice(stable: string, legacyRefresh: unknown): Promise<string> {
    const bound = this.one<{ canonical: string }>('SELECT canonical FROM device_binding WHERE id=1');
    if (bound) return bound.canonical;
    let canonical = stable;
    if (typeof legacyRefresh === 'string' && legacyRefresh) {
      const legacy = deviceFromToken(legacyRefresh);
      if (legacy !== stable) {
        // Only a valid old refresh token may link the existing account; quota/history stay in that object.
        await this.env.DEVICES.getByName(legacy).claimMigration(legacyRefresh, stable);
        canonical = legacy;
      }
    }
    this.ctx.storage.sql.exec('INSERT OR IGNORE INTO device_binding(id,canonical) VALUES (1,?)', canonical);
    return this.one<{ canonical: string }>('SELECT canonical FROM device_binding WHERE id=1')!.canonical;
  }
  async claimMigration(refresh: string, stable: string): Promise<void> {
    const session = this.one<Session>('SELECT * FROM sessions WHERE refreshHash=?', hash(refresh));
    if (!session || session.refreshUntil <= Date.now()) throw fail(401, 'Не удалось перенести сессию: срок входа истёк');
    const existing = this.one<{ stable: string }>('SELECT stable FROM migration_owner WHERE id=1');
    if (existing && existing.stable !== stable) throw fail(409, 'Сессия уже привязана к другому устройству');
    this.ctx.storage.sql.exec('INSERT OR IGNORE INTO migration_owner(id,stable) VALUES (1,?)', stable);
  }
  private issue(device: string, oldRefresh?: string) {
    const access = `v1.${device}.${randomBytes(32).toString('hex')}`;
    const refresh = `v1.${device}.${randomBytes(32).toString('hex')}`;
    const ttl = Math.max(2, Number(this.env.ACCESS_TTL_SECONDS || 60));
    this.ctx.storage.transactionSync(() => {
      if (oldRefresh) this.ctx.storage.sql.exec('DELETE FROM sessions WHERE refreshHash=?', oldRefresh);
      this.ctx.storage.sql.exec('INSERT INTO sessions VALUES (?,?,?,?)', hash(refresh), hash(access), Date.now() + ttl * 1000, Date.now() + 30 * 86400000);
    });
    return response({ access_token: access, refresh_token: refresh, expires_in: ttl, device_bound: true, ...this.publicAccount() });
  }
  private authenticate(token: string): Session {
    const session = this.one<Session>('SELECT * FROM sessions WHERE accessHash=?', hash(token));
    if (!session || session.expiresAt <= Date.now()) throw fail(401, 'Токен истёк');
    return session;
  }
  private cleanup() {
    if (Date.now() - this.lastCleanup < 60000) return;
    this.lastCleanup = Date.now();
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec('DELETE FROM events WHERE generationId IN (SELECT id FROM generations WHERE createdAt<? AND terminal IS NOT NULL)', Date.now() - 3600000);
      this.ctx.storage.sql.exec('DELETE FROM generations WHERE createdAt<? AND terminal IS NOT NULL', Date.now() - 3600000);
      this.ctx.storage.sql.exec('DELETE FROM sessions WHERE refreshUntil<?', Date.now());
    });
  }

  async handle(device: string, path: string, search: string, method: string, token: string, body: Body): Promise<Response> {
    try {
      this.cleanup();
      if (path === '/auth/device' && method === 'POST') return this.issue(device);
      if (path === '/auth/refresh' && method === 'POST') {
        const digest = hash(String(body.refresh_token || ''));
        const session = this.one<Session>('SELECT * FROM sessions WHERE refreshHash=?', digest);
        if (!session || session.refreshUntil <= Date.now()) throw fail(401, 'Сессия истекла');
        return this.issue(device, digest);
      }
      const session = this.authenticate(token);
      if (path === '/account' && method === 'GET') {
        const a = this.account();
        if (a.purchaseToken && a.verifiedAt < Date.now() - 60000) {
          try { await this.verifyPlay(a.purchaseToken, device); }
          catch (error) { if (!(error instanceof Error && 'status' in error && error.status === 400)) throw error; }
        }
        return response(this.publicAccount());
      }
      if (path === '/billing/verify' && method === 'POST') {
        await this.verifyPlay(body.purchase_token, device);
        return response(this.publicAccount());
      }
      if (path === '/generations' && method === 'POST') {
        if (!validId(body.request_id)) throw fail(400, 'Нужен request_id');
        const id = body.request_id;
        if (this.generation(id)) return response({ generation_id: id, ...this.publicAccount() });
        if (!Array.isArray(body.messages) || !body.messages.length || body.messages.length > 100 || body.messages.some(m => !m || !['user', 'assistant'].includes(m.role) || typeof m.content !== 'string' || !m.content.trim() || m.content.length > 15000) || body.messages.at(-1).role !== 'user') throw fail(400, 'Некорректные сообщения');
        if (!this.env.OPENROUTER_API_KEY) throw fail(503, 'На сервере не настроен ключ OpenRouter');
        const a = this.account();
        if (a.subscriptionUntil <= Date.now() && a.remaining <= 0) throw fail(402, 'Бесплатные сообщения закончились');
        if (this.active.size >= 2) throw fail(429, 'Сначала завершите текущий ответ');
        const messages = body.messages as Message[];
        this.ctx.storage.transactionSync(() => {
          if (a.subscriptionUntil <= Date.now()) this.ctx.storage.sql.exec('UPDATE account SET remaining=remaining-1 WHERE id=1');
          this.ctx.storage.sql.exec('INSERT INTO generations(id,messages,createdAt,reserved) VALUES (?,?,?,?)', id, JSON.stringify(messages), Date.now(), a.subscriptionUntil <= Date.now() ? 1 : 0);
        });
        this.start(id, messages);
        return response({ generation_id: id, ...this.publicAccount() });
      }
      const match = path.match(/^\/generations\/([a-zA-Z0-9-]+)\/(events|cancel|retry)$/);
      if (match) {
        const g = this.generation(match[1]);
        if (!g) throw fail(404, 'Ответ больше недоступен на сервере. Сохранённый текст остаётся в истории.');
        if (match[2] === 'retry' && method === 'POST') {
          if (!validId(body.request_id)) throw fail(400, 'Нужен request_id');
          const id = body.request_id;
          if (this.generation(id)) return response({ generation_id: id, ...this.publicAccount() });
          if (g.terminal !== 'failed') throw fail(409, 'Этот ответ не требует новой генерации');
          if (g.retries >= 3) throw fail(429, 'Лимит повторных попыток исчерпан. Отправьте новое сообщение позже.');
          if (g.retryChild) throw fail(409, 'Повторный ответ уже создан');
          if (this.active.size >= 2) throw fail(429, 'Сначала завершите текущий ответ');
          const a = this.account();
          if (a.subscriptionUntil <= Date.now() && a.remaining <= 0) throw fail(402, 'Бесплатные сообщения закончились');
          this.ctx.storage.transactionSync(() => {
            if (a.subscriptionUntil <= Date.now()) this.ctx.storage.sql.exec('UPDATE account SET remaining=remaining-1 WHERE id=1');
            this.ctx.storage.sql.exec('UPDATE generations SET retryChild=? WHERE id=?', id, g.id);
            this.ctx.storage.sql.exec('INSERT INTO generations(id,messages,retries,createdAt,reserved) VALUES (?,?,?,?,?)', id, g.messages, g.retries + 1, Date.now(), a.subscriptionUntil <= Date.now() ? 1 : 0);
          });
          this.start(id, JSON.parse(g.messages));
          return response({ generation_id: id, ...this.publicAccount() });
        }
        if (match[2] === 'cancel' && method === 'POST') {
          this.append(g.id, 'cancelled'); this.active.get(g.id)?.abort();
          return response({ ok: true, ...this.publicAccount() });
        }
        if (match[2] === 'events' && method === 'GET') {
          const after = Number(new URLSearchParams(search).get('after') || 0);
          if (!Number.isInteger(after) || after < 0 || after > g.lastEvent) throw fail(400, 'Некорректный курсор');
          return this.stream(g, after, session.expiresAt);
        }
      }
      throw fail(404, 'Не найдено');
    } catch (error) { return errorResponse(error); }
  }

  private append(id: string, type: string, payload: { text?: string; message?: string } = {}) {
    const g = this.generation(id);
    if (!g || g.terminal) return;
    const event: Event = { id: g.lastEvent + 1, type, ...payload };
    const terminal = ['done', 'failed', 'cancelled'].includes(type) ? type : null;
    this.ctx.storage.transactionSync(() => {
      if (terminal) {
        if (type !== 'done' && g.reserved) this.ctx.storage.sql.exec('UPDATE account SET remaining=remaining+1 WHERE id=1');
        this.ctx.storage.sql.exec('UPDATE generations SET reserved=0 WHERE id=?', id);
        Object.assign(event, this.publicAccount());
      }
      this.ctx.storage.sql.exec('INSERT INTO events VALUES (?,?,?)', id, event.id, JSON.stringify(event));
      this.ctx.storage.sql.exec('UPDATE generations SET lastEvent=?,terminal=? WHERE id=?', event.id, terminal, id);
    });
    for (const listener of this.listeners.get(id) || []) listener.send(event);
  }

  private stream(g: Generation, after: number, expiresAt: number): Response {
    const encoder = new TextEncoder();
    let dispose = () => {};
    const stream = new ReadableStream<Uint8Array>({
      start: controller => {
        let closed = false;
        const listeners = this.listeners.get(g.id) || new Set<Listener>();
        this.listeners.set(g.id, listeners);
        let heartbeat: ReturnType<typeof setInterval>;
        const close = (cancelled = false) => {
          if (closed) return;
          closed = true; clearInterval(heartbeat); listeners.delete(listener);
          if (!listeners.size) this.listeners.delete(g.id);
          if (!cancelled) { try { controller.close(); } catch { /* The client may have disconnected. */ } }
        };
        const write = (value: string) => { if (!closed) { try { controller.enqueue(encoder.encode(value)); } catch { close(true); } } };
        const valid = () => {
          if (expiresAt > Date.now()) return true;
          write('event: auth_expired\ndata: {"message":"Обновите токен"}\n\n'); close(); return false;
        };
        const listener: Listener = { send: event => {
          if (closed || !valid()) return;
          write(`id: ${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
          if (['done', 'failed', 'cancelled'].includes(event.type)) close();
        } };
        dispose = () => close(true);
        write(': connected\n\n');
        heartbeat = setInterval(() => { if (valid()) write(': heartbeat\n\n'); }, 5000);
        listeners.add(listener);
        for (const event of this.ctx.storage.sql.exec<{ payload: string }>('SELECT payload FROM events WHERE generationId=? AND seq>? ORDER BY seq', g.id, after)) {
          listener.send(JSON.parse(event.payload));
          if (closed) break;
        }
        if (g.terminal && !closed) close();
      },
      cancel: () => dispose(),
    });
    return new Response(stream, { headers: { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform', 'X-Accel-Buffering': 'no' } });
  }

  private start(id: string, messages: Message[]) {
    const controller = new AbortController();
    this.active.set(id, controller);
    this.ctx.waitUntil(this.generate(id, messages, controller));
  }

  private async generate(id: string, messages: Message[], controller: AbortController) {
    const timeout = setTimeout(() => controller.abort(new Error('Истекло время ожидания модели')), 180000);
    try {
      const upstream = await requestCompletion({ apiKey: this.env.OPENROUTER_API_KEY, backupKey: this.env.OPENROUTER_API_KEY_BACKUP,
        model: DEFAULT_MODEL, messages, signal: controller.signal, onAttempt: (event: { event: string; credential: string; status: number }) => console.log(JSON.stringify(event)) });
      if (!upstream.ok || !upstream.body) {
        console.warn(JSON.stringify({ event: 'provider_http_error', status: upstream.status }));
        await upstream.body?.cancel();
        throw new Error(upstream.status === 429 ? 'Бесплатная модель временно перегружена или исчерпан её лимит. Попробуйте позже.' : `Сервис AI недоступен (${upstream.status}). Нажмите «Повторить».`);
      }
      const reader = upstream.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '', length = 0, complete = false;
      try {
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r/g, '');
          if (buffer.length > 200000) throw new Error('Некорректный поток от сервиса AI');
          let boundary: number;
          while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
            const data = block.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
            if (!data) continue;
            if (data === '[DONE]') { complete = true; continue; }
            const value = JSON.parse(data);
            if (value.error) {
              console.warn(JSON.stringify({ event: 'provider_stream_error', code: value.error.code || null }));
              throw new Error('Сервис AI прервал генерацию. Полученный текст сохранён — нажмите «Повторить».');
            }
            const choice = value.choices?.[0];
            const text = choice?.delta?.content;
            if (typeof text === 'string' && text) {
              length += text.length;
              if (length > 120000) throw new Error('Ответ превысил допустимую длину');
              this.append(id, 'delta', { text });
            }
            if (choice?.finish_reason === 'error') throw new Error('Сервис AI прервал генерацию. Нажмите «Повторить».');
            if (choice?.finish_reason) complete = true;
          }
        }
        if (!complete || length === 0) throw new Error('Сервис AI не завершил ответ. Нажмите «Повторить».');
        this.append(id, 'done');
      } finally { await reader.cancel().catch(() => {}); }
    } catch (error) {
      const message = controller.signal.aborted ? 'Истекло время ожидания модели. Нажмите «Повторить».' : error instanceof Error ? error.message : 'Ошибка генерации';
      this.append(id, 'failed', { message });
    } finally { clearTimeout(timeout); this.active.delete(id); }
  }

  async grantReceipt(receiptHash: string, purchaseToken: string, expiry: number) {
    this.ctx.storage.sql.exec('UPDATE account SET subscriptionUntil=?,purchaseToken=?,receiptHash=?,verifiedAt=? WHERE id=1', expiry, purchaseToken, receiptHash, Date.now());
  }
  async revokeReceipt(receiptHash: string) {
    this.ctx.storage.sql.exec('UPDATE account SET subscriptionUntil=0,purchaseToken=NULL,receiptHash=NULL WHERE id=1 AND receiptHash=?', receiptHash);
  }
  private async verifyPlay(token: unknown, device: string) {
    if (!this.env.GOOGLE_SERVICE_ACCOUNT_JSON) throw fail(503, 'Проверка Google Play не настроена на сервере');
    if (typeof token !== 'string' || !token || token.length > 4096) throw fail(400, 'Некорректная покупка');
    const key = JSON.parse(this.env.GOOGLE_SERVICE_ACCOUNT_JSON);
    const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
    const now = Math.floor(Date.now() / 1000);
    const unsigned = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({ iss: key.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })}`;
    const assertion = unsigned + '.' + createSign('RSA-SHA256').update(unsigned).sign(key.private_key, 'base64url');
    const oauth = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }), signal: AbortSignal.timeout(15000) });
    if (!oauth.ok) throw fail(502, 'Не удалось подключиться к Google Play');
    const { access_token } = await oauth.json<{ access_token: string }>();
    const purchaseResponse = await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/dev.test.aichat/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`, { headers: { Authorization: `Bearer ${access_token}` }, signal: AbortSignal.timeout(15000) });
    if (!purchaseResponse.ok) throw fail(400, 'Google Play не подтвердил покупку');
    const purchase = await purchaseResponse.json<{ subscriptionState: string; lineItems?: { productId: string; expiryTime: string }[] }>();
    const expiry = Math.max(0, ...(purchase.lineItems || []).filter(i => ['chat_weekly', 'chat_monthly'].includes(i.productId)).map(i => Date.parse(i.expiryTime) || 0));
    if (!['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'].includes(purchase.subscriptionState) || expiry <= Date.now()) {
      await this.revokeReceipt(hash(token)); throw fail(400, 'Активная подписка не найдена');
    }
    await this.env.RECEIPTS.getByName(hash(token)).assign(device, hash(token), token, expiry);
  }
}

/** Coordinates restoration of one verified Google Play receipt across installations. */
export class PurchaseOwner extends DurableObject<Env> {
  private pending: Promise<void> = Promise.resolve();
  async assign(device: string, receiptHash: string, token: string, expiry: number): Promise<void> {
    const task = this.pending.then(async () => {
      const previous = await this.ctx.storage.get<string>('owner');
      if (previous && previous !== device) await this.env.DEVICES.getByName(previous).revokeReceipt(receiptHash);
      await this.env.DEVICES.getByName(device).grantReceipt(receiptHash, token, expiry);
      await this.ctx.storage.put('owner', device);
    });
    this.pending = task.catch(() => {});
    return task;
  }
}
