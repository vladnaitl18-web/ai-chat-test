import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requestCompletion } from './provider.mjs';

for (const [name, status, metadata, attempts] of [
  ['rejected key', 401, {}, 2],
  ['per-key spending cap', 402, { limit_source: 'openrouter_key_limit' }, 2],
  ['account balance', 402, { limit_source: 'openrouter_credits' }, 1],
  ['rate limit with separate backup account', 429, {}, 2],
  ['upstream failure', 503, {}, 2],
]) test(`credential fallback: ${name}`, async () => {
  const calls = [];
  const response = await requestCompletion({ apiKey: 'primary-test', backupKey: 'backup-test', model: 'test:free', messages: [], fetcher: async (_, init) => {
    calls.push(init.headers.Authorization);
    return calls.length === 1 ? Response.json({ error: { metadata } }, { status }) : new Response('SSE stream');
  } });
  assert.equal(calls.length, attempts);
  if (attempts === 2) { assert.deepEqual(calls, ['Bearer primary-test', status === 503 ? 'Bearer primary-test' : 'Bearer backup-test']); assert.equal(await response.text(), 'SSE stream'); }
});
test('credential fallback: never loops and honors retry hints', async () => {
  for (const headers of [{}, { 'Retry-After': '60' }]) {
    let calls = 0;
    await requestCompletion({ apiKey: 'a', backupKey: 'b', model: 'test:free', messages: [], fetcher: async () => { calls++; return new Response('', { status: 401, headers }); } });
    assert.equal(calls, headers['Retry-After'] ? 1 : 2);
  }
});
