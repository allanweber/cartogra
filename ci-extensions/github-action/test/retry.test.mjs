import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runAction, ok, apiError, response, checkResult } from './helpers.mjs';

test('S-14: a 429 waits Retry-After seconds, then retries', async () => {
  const r = await runAction({
    responses: [response(429, { error: { code: 'RATE_LIMITED', message: 'slow down' } }, { 'Retry-After': '1' }),
      ok(checkResult())],
  });
  assert.equal(r.requests.length, 2);
  assert.deepEqual(r.sleeps, [1000]);
  assert.ok(r.requests[1].at - r.requests[0].at >= 1000);
  assert.equal(r.exitCode, 0);
});

test('S-14: Retry-After is capped at 30 s, and a missing Retry-After waits 2 s', async () => {
  const r = await runAction({
    responses: [response(429, '', { 'Retry-After': '120' }), response(429, ''), ok(checkResult())],
  });
  assert.deepEqual(r.sleeps, [30000, 2000]);
  assert.equal(r.exitCode, 0);
});

test('S-15: three tries on 5xx with waits of 2 s and 4 s, then fail', async () => {
  const r = await runAction({ responses: [apiError(503, 'UNAVAILABLE')] });
  assert.equal(r.requests.length, 3);
  assert.deepEqual(r.sleeps, [2000, 4000]);
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*503 UNAVAILABLE/);
  assert.equal(r.output, '');
});

test('S-15: a 429 on the last try fails', async () => {
  const r = await runAction({ responses: [response(429, { error: { code: 'RATE_LIMITED', message: 'm' } }, { 'Retry-After': '1' })] });
  assert.equal(r.requests.length, 3);
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*429 RATE_LIMITED/);
});

test('S-15: a network error retries, then fails after 3 tries', async () => {
  const r = await runAction({ responses: [new TypeError('fetch failed')] });
  assert.equal(r.requests.length, 3);
  assert.deepEqual(r.sleeps, [2000, 4000]);
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*Network error: fetch failed/);
});

test('S-15: a timeout retries, and each try has a 30 s timeout signal', async () => {
  const timeout = new DOMException('The operation timed out.', 'TimeoutError');
  const r = await runAction({ responses: [timeout, ok(checkResult())] });
  assert.equal(r.requests.length, 2);
  assert.equal(r.exitCode, 0);
  for (const req of r.requests) assert.ok(req.init.signal instanceof AbortSignal);

  const failed = await runAction({ responses: [timeout] });
  assert.equal(failed.exitCode, 1);
  assert.match(failed.stdout, /::error.*Timeout after 30 s/);
});

test('S-16: a 4xx does not retry', async () => {
  for (const status of [400, 401, 403, 404, 409, 422]) {
    const r = await runAction({ responses: [apiError(status, 'SOME_CODE')] });
    assert.equal(r.requests.length, 1, String(status));
    assert.deepEqual(r.sleeps, []);
  }
});

test('S-15: a timeout while the body is read retries', async () => {
  const timeout = new DOMException('The operation timed out.', 'TimeoutError');
  const slowBody = { ...ok(checkResult()), text: async () => { throw timeout; } };
  const r = await runAction({ responses: [slowBody, ok(checkResult())] });
  assert.equal(r.requests.length, 2);
  assert.deepEqual(r.sleeps, [2000]);
  assert.equal(r.exitCode, 0);

  const failed = await runAction({ responses: [slowBody] });
  assert.equal(failed.requests.length, 3);
  assert.equal(failed.exitCode, 1);
  assert.match(failed.stdout, /::error.*Timeout after 30 s/);
});
