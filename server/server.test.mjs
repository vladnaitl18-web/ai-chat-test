import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createChatServer } from './server.mjs';

// Isolated test transport; no application runtime modes or substitute AI responses.
function upstream(parts = ['## День 1\n', 'Первый пункт. ', 'Продолжение.'], delay = 15, fail = false) {
  return async () => new Response(new ReadableStream({
    async start(controller) {
      const enc = new TextEncoder();
      for (const content of parts) {
        await new Promise(r => setTimeout(r, delay));
        controller.enqueue(enc.encode(`data: ${JSON.stringify({ choices: [{ delta: { content } }] })}\n\n`));
      }
      controller.enqueue(enc.encode(fail ? 'data: {"error":{"message":"upstream error"}}\n\n' : 'data: [DONE]\n\n'));
      controller.close();
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } });
}
async function start(t, config = {}) {
  const app = createChatServer({ apiKey: 'test-only-key', dataFile: null, fetch: upstream(), ...config });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  const base = `http://127.0.0.1:${app.server.address().port}`;
  t.after(() => new Promise(resolve => { app.server.close(resolve); app.server.closeAllConnections(); }));
  async function request(path, token, body) {
    const res = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    return { status: res.status, data: await res.json() };
  }
  const device = randomUUID();
  const login = await request('/auth/device', null, { device_id: device });
  const token = login.data.access_token;
  const generate = (id = randomUUID()) => request('/generations', token, { request_id: id, messages: [{ role: 'user', content: 'Вопрос' }] });
  const events = (id, cursor = 0, access = token) => fetch(`${base}/generations/${id}/events?after=${cursor}`, { headers: { Authorization: `Bearer ${access}` } });
  return { ...app, request, device, login: login.data, token, generate, events };
}
function parse(text) {
  return text.split('\n\n').filter(b => b.includes('data:')).map(b => ({ type: b.match(/event: (.+)/)?.[1], ...JSON.parse(b.match(/data: (.+)/)[1]) }));
}
test('device authentication and counter persist across server restart', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'chat-test-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  const a = await start(t, { dataFile: join(dir, 'state.json') });
  assert.equal(a.login.remaining, 3);
  const completed = await a.generate(); await (await a.events(completed.data.generation_id)).text();
  const b = await start(t, { dataFile: join(dir, 'state.json') });
  assert.equal((await b.request('/auth/device', null, { device_id: a.device })).data.remaining, 2);
});
test('idempotent generation charges once; fourth distinct message hits paywall', async t => {
  const app = await start(t), id = randomUUID();
  const one = await app.generate(id), two = await app.generate(id);
  assert.equal(one.data.generation_id, two.data.generation_id);
  assert.equal(two.data.remaining, 2); assert.equal(app.metrics.generations, 1);
  await (await app.events(id)).text();
  for (let i = 0; i < 2; i++) { const r = await app.generate(); await (await app.events(r.data.generation_id)).text(); }
  assert.equal((await app.generate()).status, 402);
});
test('missing OpenRouter key returns error without charging', async t => {
  const app = await start(t, { apiKey: '' });
  const response = await app.generate();
  assert.equal(response.status, 503); assert.match(response.data.message, /ключ OpenRouter/);
  assert.equal((await app.request('/account', app.token)).data.remaining, 3);
});
test('refresh during SSE resumes same generation without duplicated text', async t => {
  const parts = ['A', 'Б', 'C', 'Д', 'E', 'Ф', 'G', 'H'];
  const app = await start(t, { fetch: upstream(parts, 25) });
  const r = await app.generate(), response = await app.events(r.data.generation_id);
  setTimeout(() => { for (const s of Object.values(app.state.sessions)) s.expiresAt = 0; }, 65);
  const first = parse(await response.text());
  assert.equal(first.at(-1).type, 'auth_expired');
  const deltas = first.filter(e => e.type === 'delta'); assert.ok(deltas.length > 0);
  const refresh = await app.request('/auth/refresh', null, { refresh_token: app.login.refresh_token });
  assert.equal(refresh.status, 200);
  const rest = parse(await (await app.events(r.data.generation_id, deltas.at(-1).id, refresh.data.access_token)).text());
  assert.equal(rest.at(-1).type, 'done');
  assert.equal([...deltas, ...rest.filter(e => e.type === 'delta')].map(e => e.text).join(''), parts.join(''));
  assert.equal(app.metrics.generations, 1); assert.equal(app.metrics.refreshes, 1);
});
test('network disconnect preserves generation for exact replay', async t => {
  const parts = ['one', 'two', 'three', 'four'];
  const app = await start(t, { fetch: upstream(parts, 25) });
  const r = await app.generate(), response = await app.events(r.data.generation_id), reader = response.body.getReader();
  const first = parse(new TextDecoder().decode((await reader.read()).value));
  await reader.cancel();
  const rest = parse(await (await app.events(r.data.generation_id, first.at(-1).id)).text());
  assert.equal([...first, ...rest].filter(e => e.type === 'delta').map(e => e.text).join(''), parts.join(''));
  assert.equal(app.metrics.generations, 1);
});
test('stop is terminal and cannot append more text', async t => {
  const app = await start(t, { fetch: upstream(['a','b','c','d'], 25) });
  const r = await app.generate(); await new Promise(r => setTimeout(r, 40));
  await app.request(`/generations/${r.data.generation_id}/cancel`, app.token, {});
  const result = parse(await (await app.events(r.data.generation_id)).text());
  assert.equal(result.at(-1).type, 'cancelled');
  assert.equal(result.at(-1).remaining, 3);
  await new Promise(r => setTimeout(r, 100));
  assert.equal(parse(await (await app.events(r.data.generation_id)).text()).length, result.length);
});
test('provider error retains partial text; manual retry does not charge again', async t => {
  let calls = 0;
  const app = await start(t, { fetch: (...args) => (++calls === 1 ? upstream(['partial'], 1, true) : upstream(['complete'], 1))(...args) });
  const r = await app.generate(), first = parse(await (await app.events(r.data.generation_id)).text());
  assert.equal(first[0].text, 'partial'); assert.equal(first.at(-1).type, 'failed');
  assert.equal(first.at(-1).remaining, 3);
  assert.equal((await app.request('/account', app.token)).data.remaining, 3);
  const retried = await app.request(`/generations/${r.data.generation_id}/retry`, app.token, { request_id: randomUUID() });
  assert.equal(retried.data.remaining, 2);
  const last = parse(await (await app.events(retried.data.generation_id)).text());
  assert.equal(last[0].text, 'complete'); assert.equal(last.at(-1).type, 'done');
  assert.equal(last.at(-1).remaining, 2);
});
test('device isolation, refresh validation, no mock purchase endpoint', async t => {
  const app = await start(t), r = await app.generate();
  const stranger = (await app.request('/auth/device', null, { device_id: randomUUID() })).data.access_token;
  assert.equal((await app.events(r.data.generation_id, 0, stranger)).status, 404);
  assert.equal((await app.request(`/generations/${r.data.generation_id}/cancel`, stranger, {})).status, 404);
  assert.equal((await app.request('/auth/refresh', null, { refresh_token: 'invalid' })).status, 401);
  assert.equal((await app.request('/billing/mock', app.token, {})).status, 404);
});
