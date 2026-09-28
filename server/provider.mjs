import { completionRequest } from './prompt.mjs';

// One credential failover before streaming; the backup belongs to a separate authorized account.
export async function requestCompletion({ apiKey, backupKey, model, messages, signal, fetcher = fetch }) {
  const send = key => fetcher('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST', signal,
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json', 'X-Title': 'Android AI Chat Test' },
    body: JSON.stringify(completionRequest(model, messages)),
  });
  const response = await send(apiKey);
  if (!backupKey || backupKey === apiKey || signal?.aborted) return response;
  let keyRejected = response.status === 401 || response.status === 429;
  if (response.status === 402) {
    const error = await response.clone().json().catch(() => ({}));
    keyRejected = error?.error?.metadata?.limit_source === 'openrouter_key_limit';
  }
  if (!keyRejected) return response;
  const retryAfter = response.headers.get('Retry-After');
  if (retryAfter) {
    const seconds = Number(retryAfter);
    const delay = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(retryAfter) - Date.now();
    // Longer limits are reported to the user; do not keep a mobile request retrying indefinitely.
    if (!Number.isFinite(delay) || delay > 3000) return response;
    if (delay > 0) await new Promise((resolve, reject) => {
      const onAbort = () => { clearTimeout(timer); reject(signal.reason); };
      const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve(); }, delay);
      signal?.addEventListener('abort', onAbort, { once: true });
    });
  }
  signal?.throwIfAborted();
  await response.body?.cancel();
  return send(backupKey);
}
