import { completionRequest } from './prompt.mjs';

// One credential failover before streaming; the backup belongs to a separate authorized account.
export async function requestCompletion({ apiKey, backupKey, model, messages, signal, fetcher = fetch, onAttempt = (_event) => {} }) {
  const send = key => fetcher('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST', signal,
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json', 'X-Title': 'Android AI Chat Test' },
    body: JSON.stringify(completionRequest(model, messages)),
  });
  let response = await send(apiKey);
  onAttempt({ event: 'provider_attempt', credential: 'primary', status: response.status });
  // One short retry for temporary upstream failures, before any text has been delivered.
  if ([408, 500, 502, 503, 504].includes(response.status) && await waitForRetry(response, signal, 1000)) {
    await response.body?.cancel();
    response = await send(apiKey);
    onAttempt({ event: 'provider_retry', credential: 'primary', status: response.status });
  }
  if (!backupKey || backupKey === apiKey || signal?.aborted) return response;
  let keyRejected = response.status === 401 || response.status === 429;
  if (response.status === 402) {
    const error = await response.clone().json().catch(() => ({}));
    keyRejected = error?.error?.metadata?.limit_source === 'openrouter_key_limit';
  }
  if (!keyRejected) return response;
  if (!await waitForRetry(response, signal, 0)) return response;
  signal?.throwIfAborted();
  await response.body?.cancel();
  const backup = await send(backupKey);
  onAttempt({ event: 'provider_fallback', credential: 'backup', status: backup.status });
  return backup;
}

async function waitForRetry(response, signal, minimum) {
  signal?.throwIfAborted();
  const hint = response.headers.get('Retry-After');
  const seconds = hint == null ? 0 : Number(hint);
  const requested = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(hint) - Date.now();
  if (!Number.isFinite(requested) || requested > 3000) return false;
  const delay = Math.max(minimum, requested);
  if (delay > 0) await new Promise((resolve, reject) => {
    const onAbort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve(); }, delay);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
  return true;
}
