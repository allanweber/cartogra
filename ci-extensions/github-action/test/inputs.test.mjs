import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runAction, ok, checkResult } from './helpers.mjs';
import { MAX_SPEC_CHARS } from '../index.mjs';

test('S-1: an empty required input fails before a call', async () => {
  for (const name of ['api-key', 'service', 'spec']) {
    const r = await runAction({ inputs: { [name]: '  ' }, responses: [ok(checkResult())] });
    assert.equal(r.exitCode, 1, name);
    assert.match(r.stdout, new RegExp(`::error.*Input required: ${name}`));
    assert.equal(r.requests.length, 0, name);
  }
});

test('S-2: a bad fail-on-breaking value fails before a call', async () => {
  const r = await runAction({ inputs: { 'fail-on-breaking': 'yes' }, responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*fail-on-breaking/);
  assert.equal(r.requests.length, 0);
});

test('S-2: an empty fail-on-breaking means true', async () => {
  const r = await runAction({ inputs: { 'fail-on-breaking': '' }, responses: [ok(checkResult({ isBreaking: true }))] });
  assert.equal(r.exitCode, 1);
});

test('S-3: plain http to a remote host fails before a call', async () => {
  const r = await runAction({ inputs: { url: 'http://cartogra.example.com' }, responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*must use https/);
  assert.equal(r.requests.length, 0);
});

test('S-3: a url that is not a URL fails before a call', async () => {
  const r = await runAction({ inputs: { url: 'not a url' }, responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 1);
  assert.equal(r.requests.length, 0);
});

test('S-4: http to localhost calls the check path with no double slash', async () => {
  for (const [url, expected] of [
    ['http://localhost:8080/', 'http://localhost:8080/api/v1/contracts/check'],
    ['http://127.0.0.1:8080', 'http://127.0.0.1:8080/api/v1/contracts/check'],
    ['https://cartogra.example.com//', 'https://cartogra.example.com/api/v1/contracts/check'],
  ]) {
    const r = await runAction({ inputs: { url }, responses: [ok(checkResult())] });
    assert.equal(r.exitCode, 0, url);
    assert.equal(r.requests[0].url, expected);
  }
});

test('S-4: the default url is https://cartogra.dev', async () => {
  const r = await runAction({ inputs: { url: '' }, responses: [ok(checkResult())] });
  assert.equal(r.requests[0].url, 'https://cartogra.dev/api/v1/contracts/check');
});

test('S-5: a missing spec file fails before a call and names the file', async () => {
  const r = await runAction({ inputs: { spec: 'missing.yaml' }, spec: null, responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*missing\.yaml/);
  assert.equal(r.requests.length, 0);
});

test('S-6: a spec of more than 1,048,576 characters fails before a call', async () => {
  const r = await runAction({ spec: 'a'.repeat(MAX_SPEC_CHARS + 1), responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 1);
  assert.match(r.stdout, /::error.*1 MiB limit/);
  assert.equal(r.requests.length, 0);
});

test('S-6: a spec of exactly 1,048,576 characters is sent', async () => {
  const r = await runAction({ spec: 'a'.repeat(MAX_SPEC_CHARS), responses: [ok(checkResult())] });
  assert.equal(r.exitCode, 0);
  assert.equal(r.requests.length, 1);
});
