import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runAction, ok, apiError, response, checkResult, change, consumer, outputs, SPEC } from './helpers.mjs';

test('S-7: the key is masked first, sent only in the header, and found nowhere else', async () => {
  const r = await runAction({
    inputs: { 'api-key': 'ck_secret' },
    responses: [apiError(401, 'UNAUTHORIZED', 'bad key ck_secret')],
  });
  assert.equal(r.exitCode, 1);
  const lines = r.stdout.split('\n');
  assert.equal(lines[0], '::add-mask::ck_secret');
  const req = r.requests[0];
  assert.equal(req.init.method, 'POST');
  assert.equal(req.init.headers['X-Cartogra-Api-Key'], 'ck_secret');
  assert.equal(req.init.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(req.init.body), { service: 'ledger-service', spec: SPEC });
  assert.ok(!req.url.includes('ck_secret'));
  assert.ok(!lines.slice(1).join('\n').includes('ck_secret'));
  assert.ok(!r.summary.includes('ck_secret'));
  assert.ok(!r.output.includes('ck_secret'));
});

test('S-8: a breaking check fails the step and sets the outputs', async () => {
  const consumers = Array.from({ length: 12 }, (_, i) => consumer(`consumer-${i}`, i));
  const r = await runAction({
    responses: [ok(checkResult({ isBreaking: true, changes: [change('response-field-removed'), change('path-removed')], consumers }))],
  });
  assert.equal(r.exitCode, 1);
  assert.deepEqual(outputs(r.output), { 'is-breaking': 'true', 'changes-count': '2' });
});

test('S-9: a breaking check with fail-on-breaking false passes with a warning', async () => {
  const r = await runAction({
    inputs: { 'fail-on-breaking': 'false' },
    responses: [ok(checkResult({ isBreaking: true, changes: [change('path-removed')] }))],
  });
  assert.equal(r.exitCode, 0);
  assert.match(r.stdout, /^::warning /m);
  assert.doesNotMatch(r.stdout, /^::error/m);
  assert.equal(outputs(r.output)['is-breaking'], 'true');
});

test('S-10: a compatible check passes and sets the outputs', async () => {
  const r = await runAction({ responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 0);
  assert.deepEqual(outputs(r.output), { 'is-breaking': 'false', 'changes-count': '0' });
  assert.doesNotMatch(r.stdout, /^::(error|warning)/m);
});

test('S-11: no baseline passes with a warning', async () => {
  const r = await runAction({ responses: [apiError(404, 'BASELINE_NOT_FOUND')] });
  assert.equal(r.exitCode, 0);
  assert.match(r.stdout, /^::warning /m);
  assert.deepEqual(outputs(r.output), { 'is-breaking': 'false', 'changes-count': '0' });
});

for (const [status, code] of [
  [401, 'UNAUTHORIZED'], [403, 'FORBIDDEN'], [404, 'SERVICE_NOT_FOUND'],
  [409, 'SERVICE_AMBIGUOUS'], [422, 'INVALID_SPEC'], [400, 'VALIDATION_ERROR'],
]) {
  test(`S-12: ${status} ${code} fails with the status and the code, and sets no outputs`, async () => {
    const r = await runAction({ responses: [apiError(status, code, `the ${code} message`)] });
    assert.equal(r.exitCode, 1);
    assert.match(r.stdout, new RegExp(`::error.*${status} ${code}: the ${code} message`));
    assert.equal(r.output, '');
  });
}

test('S-12: a 200 with no check result fails', async () => {
  const r = await runAction({ responses: [response(200, { data: null })] });
  assert.equal(r.exitCode, 1);
  assert.equal(r.output, '');
});

test('S-13: a response that is not JSON fails with the status', async () => {
  const r = await runAction({ responses: [response(502, '<html><body>Bad gateway</body></html>')] });
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*502.*not JSON/);
  assert.equal(r.output, '');
});

test('S-10: the compatible message uses "change" for 1 and "changes" for more', async () => {
  const one = await runAction({ responses: [ok(checkResult({ changes: [change('path.added', false)] }))] });
  assert.match(one.stdout, /is compatible \(1 change\)\./);
  const two = await runAction({
    responses: [ok(checkResult({ changes: [change('path.added', false), change('field.added', false)] }))],
  });
  assert.match(two.stdout, /is compatible \(2 changes\)\./);
});
