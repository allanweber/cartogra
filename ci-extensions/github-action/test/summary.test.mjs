import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runAction, ok, checkResult, change, consumer } from './helpers.mjs';

test('S-8: the summary has the changes table, the consumers, and one error annotation', async () => {
  const consumers = Array.from({ length: 12 }, (_, i) => consumer(`consumer-${i}`, i));
  const r = await runAction({
    responses: [ok(checkResult({ isBreaking: true, baselineVersion: 7,
      changes: [change('response-field-removed'), change('path-removed')], consumers }))],
  });
  assert.match(r.summary, /\*\*Service:\*\* ledger-service/);
  assert.match(r.summary, /\*\*Baseline version:\*\* 7/);
  assert.match(r.summary, /\*\*Result:\*\* Breaking/);
  const rows = r.summary.split('\n').filter((l) => l.startsWith('| ') && !l.startsWith('| Rule') && !l.startsWith('| ---'));
  assert.equal(rows.length, 2);
  assert.equal(rows[0], '| response-field-removed | breaking | GET /accounts/{id} response.200.balance |');
  for (let i = 0; i < 12; i++) assert.ok(r.summary.includes(`consumer-${i}`), `consumer-${i}`);

  const errors = r.stdout.split('\n').filter((l) => l.startsWith('::error'));
  assert.equal(errors.length, 1);
  assert.match(errors[0], /ledger-service/);
  assert.match(errors[0], /2 breaking changes/);
});

test('S-8: a consumer with no name shows its id', async () => {
  const r = await runAction({
    responses: [ok(checkResult({ isBreaking: true, changes: [change('path-removed')],
      consumers: [{ ...consumer(null, 5), serviceName: null }] }))],
  });
  assert.ok(r.summary.includes('00000000-0000-0000-0000-000000000005'));
});

test('S-10: a compatible check with no changes says "No changes"', async () => {
  const r = await runAction({ responses: [ok(checkResult())] });
  assert.match(r.summary, /\*\*Result:\*\* Compatible/);
  assert.match(r.summary, /^No changes$/m);
  assert.doesNotMatch(r.summary, /\| Rule/);
});

test('S-17: the summary escapes Markdown and HTML in response values', async () => {
  const r = await runAction({
    responses: [ok(checkResult({ isBreaking: true,
      changes: [{ ...change('`rule`|x'), location: '<b>&</b>' }],
      consumers: [consumer('<img src=x>|svc')] }))],
  });
  assert.ok(!r.summary.includes('<img'));
  assert.ok(!r.summary.includes('<b>'));
  assert.ok(r.summary.includes('&lt;img src=x&gt;&#124;svc'));
  assert.ok(r.summary.includes('&#96;rule&#96;&#124;x'));
  assert.ok(r.summary.includes('&lt;b&gt;&amp;&lt;/b&gt;'));
  const row = r.summary.split('\n').find((l) => l.includes('rule&#96;'));
  assert.equal(row.split('|').length, 5, 'the row has 3 cells and no extra pipe');
});

test('S-17: the summary escapes link and emphasis syntax in response values', async () => {
  const r = await runAction({
    responses: [ok(checkResult({ isBreaking: true,
      changes: [{ ...change('**bold**'), location: '[x](https://evil.example)' }],
      consumers: [consumer('_svc_~~old~~\\')] }))],
  });
  assert.ok(!r.summary.includes('[x]('));
  assert.ok(!r.summary.includes('**bold**'));
  assert.ok(r.summary.includes('&#91;x&#93;&#40;https://evil.example&#41;'));
  assert.ok(r.summary.includes('&#42;&#42;bold&#42;&#42;'));
  assert.ok(r.summary.includes('&#95;svc&#95;&#126;&#126;old&#126;&#126;&#92;'));
});

test('S-7: a key that the server sends back is not in the summary', async () => {
  const r = await runAction({
    responses: [ok(checkResult({ isBreaking: true,
      changes: [{ ...change('rule ck_test'), location: 'at ck_test' }],
      consumers: [consumer('svc-ck_test')] }))],
  });
  assert.ok(!r.summary.includes('ck_test'));
  assert.ok(!r.summary.includes('ck&#95;test'));
  assert.ok(r.summary.includes('***') || r.summary.includes('&#42;&#42;&#42;'));
});
