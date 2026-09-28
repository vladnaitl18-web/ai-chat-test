import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

// Only this isolated test process substitutes upstream HTTP; the deployed Worker never has a demo mode.
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function provider(parts = ['Часть ', 'ответа'], delay = 5, failed = false) {
  return async request => {
    assert.equal(new URL(request.url).hostname, 'openrouter.ai');
    const body = await request.json();
    assert.equal(body.models.length, 2);
    assert.ok(body.models.every(model => model.endsWith(':free')));
    assert.deepEqual(body.provider.max_price, { prompt: 0, completion: 0 });
    return new Response(new ReadableStream({ async start(controller) {
      const encoder = new TextEncoder();
      for (const text of parts) {
        await sleep(delay);
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ choices: [{ delta: { content: text } }] })}\n\n`));
      }
      controller.enqueue(encoder.encode(failed ? 'data: {"error":{"code":503}}\n\n' : 'data: [DONE]\n\n'));
      controller.close();
    } }), { headers: { 'Content-Type': 'text/event-stream' } });
  };
}
function options(overrides = {}) {
  return {
    name: 'ai-chat-cloud-tests', modules: true, scriptPath: 'dist/index.js', compatibilityDate: '2026-09-28', compatibilityFlags: ['nodejs_compat'],
    bindings: { OPENROUTER_API_KEY: 'test-only', ACCESS_TTL_SECONDS: '60' },
    durableObjects: { DEVICES: { className: 'DeviceChat', useSQLite: true }, RECEIPTS: { className: 'PurchaseOwner', useSQLite: true } },
    ratelimits: { AUTH_LIMIT: { namespace_id: '1001', simple: { limit: 30, period: 60 } } },
    outboundService: provider(), ...overrides,
  };
}
async function start(t, overrides) {
  const mf = new Miniflare(convertV4MiniflareOptions(options(overrides)));
  t.after(() => mf.dispose());
  const request = async (path, token, body) => {
    const res = await mf.dispatchFetch('https://test.local' + path, {
      method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, data: await res.json() };
  };
  const device = randomUUID();
  const login = (await request('/auth/device', null, { device_id: device })).data;
  const token = login.access_token;
  const generate = (id = randomUUID()) => request('/generations', token, { request_id: id, messages: [{ role: 'user', content: 'Вопрос' }] });
  const events = (id, after = 0, access = token) => mf.dispatchFetch(`https://test.local/generations/${id}/events?after=${after}`, { headers: { Authorization: 'Bearer ' + access } });
  return { mf, request, device, login, token, generate, events };
}
function parse(text) {
  return text.split('\n\n').filter(block => block.includes('data:')).map(block => ({ type: block.match(/event: (.+)/)?.[1], ...JSON.parse(block.match(/data: (.+)/)[1]) }));
}

test('cloud: persistent quota, idempotent requests, paywall after three successes', async t => {
  const app = await start(t), id = randomUUID();
  assert.equal(app.login.remaining, 3);
  await app.generate(id);
  assert.equal((await app.generate(id)).data.remaining, 2);
  assert.equal(parse(await (await app.events(id)).text()).at(-1).type, 'done');
  for (let i = 0; i < 2; i++) {
    const r = await app.generate(); await (await app.events(r.data.generation_id)).text();
  }
  assert.equal((await app.generate()).status, 402);
  assert.equal((await app.request('/account', app.token)).data.remaining, 0);
});
test('cloud: failure refunds once, retains partial text, successful retry charges once', async t => {
  let calls = 0;
  const app = await start(t, { outboundService: request => (++calls === 1 ? provider(['Фрагмент'], 5, true) : provider(['Готово']))(request) });
  const r = await app.generate();
  const first = parse(await (await app.events(r.data.generation_id)).text());
  assert.equal(first[0].text, 'Фрагмент'); assert.equal(first.at(-1).remaining, 3);
  assert.equal((await app.request('/account', app.token)).data.remaining, 3);
  await (await app.events(r.data.generation_id)).text();
  assert.equal((await app.request('/account', app.token)).data.remaining, 3);
  const retry = await app.request(`/generations/${r.data.generation_id}/retry`, app.token, { request_id: randomUUID() });
  const last = parse(await (await app.events(retry.data.generation_id)).text());
  assert.equal(last.at(-1).type, 'done'); assert.equal(last.at(-1).remaining, 2);
});
test('cloud: disconnected SSE resumes without duplicates or another charge', async t => {
  const app = await start(t, { outboundService: provider(['a', 'б', 'c', 'д'], 70) });
  const r = await app.generate(), stream = await app.events(r.data.generation_id), reader = stream.body.getReader();
  let first = [];
  while (!first.length) first = parse(new TextDecoder().decode((await reader.read()).value));
  await reader.cancel();
  const rest = parse(await (await app.events(r.data.generation_id, first.at(-1).id)).text());
  assert.equal([...first, ...rest].filter(e => e.type === 'delta').map(e => e.text).join(''), 'aбcд');
  assert.equal(rest.at(-1).remaining, 2);
});
test('cloud: token expiry midstream refreshes and preserves exact text', async t => {
  const app = await start(t, { bindings: { OPENROUTER_API_KEY: 'test-only', ACCESS_TTL_SECONDS: '2' }, outboundService: provider(['A', 'Б', 'C', 'Д'], 650) });
  const r = await app.generate(), first = parse(await (await app.events(r.data.generation_id)).text());
  assert.equal(first.at(-1).type, 'auth_expired');
  const cursor = first.filter(e => e.type === 'delta').at(-1).id;
  const refresh = await app.request('/auth/refresh', null, { refresh_token: app.login.refresh_token });
  const rest = parse(await (await app.events(r.data.generation_id, cursor, refresh.data.access_token)).text());
  assert.equal([...first, ...rest].filter(e => e.type === 'delta').map(e => e.text).join(''), 'AБCД');
  assert.equal(rest.at(-1).type, 'done');
});
test('cloud: cancellation refunds once and is terminal, devices are isolated', async t => {
  const app = await start(t, { outboundService: provider(['a', 'b', 'c'], 80) });
  const r = await app.generate(); await sleep(110);
  const cancelled = await app.request(`/generations/${r.data.generation_id}/cancel`, app.token, {});
  assert.equal(cancelled.data.remaining, 3);
  const first = parse(await (await app.events(r.data.generation_id)).text());
  assert.equal(first.at(-1).type, 'cancelled');
  await app.request(`/generations/${r.data.generation_id}/cancel`, app.token, {});
  assert.equal((await app.request('/account', app.token)).data.remaining, 3);
  const other = (await app.request('/auth/device', null, { device_id: randomUUID() })).data.access_token;
  assert.equal((await app.events(r.data.generation_id, 0, other)).status, 404);
});
test('cloud: account and completed SSE survive runtime restart', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'chat-cloud-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  const app = await start(t, { resourcePersistencePath: dir });
  const r = await app.generate(); const expected = await (await app.events(r.data.generation_id)).text();
  await app.mf.dispose();
  const next = await start(t, { resourcePersistencePath: dir });
  const restored = await next.request('/account', app.token);
  assert.equal(restored.data.remaining, 2, JSON.stringify(restored));
  assert.equal(await (await next.events(r.data.generation_id, 0, app.token)).text(), expected);
});
test('cloud: migration keeps exhausted quota and SSE after reinstall', async t => {
  const app = await start(t);
  let first;
  for (let i = 0; i < 3; i++) {
    const r = await app.generate(); first ??= r.data.generation_id;
    await (await app.events(r.data.generation_id)).text();
  }
  const stable = 'android-' + 'a'.repeat(64);
  const migrated = await app.request('/auth/device', null, { device_id: stable, legacy_refresh_token: app.login.refresh_token });
  assert.equal(migrated.status, 200); assert.equal(migrated.data.remaining, 0);
  assert.equal(migrated.data.device_bound, true);
  const reinstall = await app.request('/auth/device', null, { device_id: stable });
  assert.equal(reinstall.data.remaining, 0);
  assert.equal((await app.events(first, 0, reinstall.data.access_token)).status, 200);
  assert.equal((await app.request('/generations', reinstall.data.access_token, { request_id: randomUUID(), messages: [{ role: 'user', content: 'Вопрос' }] })).status, 402);
});
test('cloud: migration needs valid credentials and cannot rebind an account', async t => {
  const app = await start(t);
  const first = 'android-' + 'b'.repeat(64), second = 'android-' + 'c'.repeat(64);
  assert.notEqual((await app.request('/auth/device', null, { device_id: first, legacy_refresh_token: 'invalid' })).status, 200);
  assert.equal((await app.request('/auth/device', null, { device_id: first, legacy_refresh_token: app.login.refresh_token })).status, 200);
  assert.notEqual((await app.request('/auth/device', null, { device_id: second, legacy_refresh_token: app.login.refresh_token })).status, 200);
  assert.equal((await app.request('/auth/device', null, { device_id: first })).data.remaining, 3);
});

test('cloud: operator epoch resets exhausted quotas once without erasing sessions or history', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'chat-reset-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  const app = await start(t, { resourcePersistencePath: dir });
  let first;
  for (let i = 0; i < 3; i++) {
    const result = await app.generate(); first ??= result.data.generation_id;
    await (await app.events(result.data.generation_id)).text();
  }
  assert.equal((await app.request('/account', app.token)).data.remaining, 0);
  await app.mf.dispose();
  const options = { resourcePersistencePath: dir, bindings: { OPENROUTER_API_KEY: 'test-only', ACCESS_TTL_SECONDS: '60', QUOTA_RESET_EPOCH: 'maintenance-test-1' } };
  const next = await start(t, options);
  assert.equal((await next.request('/account', app.token)).data.remaining, 3);
  assert.equal((await next.events(first, 0, app.token)).status, 200);
  const generation = await next.request('/generations', app.token, { request_id: randomUUID(), messages: [{ role: 'user', content: 'Вопрос' }] });
  await (await next.events(generation.data.generation_id, 0, app.token)).text();
  assert.equal((await next.request('/account', app.token)).data.remaining, 2);
  assert.equal((await next.request('/account', app.token)).data.remaining, 2);
  await next.mf.dispose();
  const restarted = await start(t, options);
  assert.equal((await restarted.request('/account', app.token)).data.remaining, 2);
});
