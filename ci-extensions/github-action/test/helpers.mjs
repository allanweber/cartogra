// Test harness for run(...): temporary workspace, fake fetch, recorded sleep, captured stdout.
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { run } from '../index.mjs';

export const SPEC = 'openapi: 3.0.3\ninfo: { title: ledger, version: "1" }\npaths: {}\n';

/** A fake Response with the parts that index.mjs uses. */
export function response(status, body, headers = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const lower = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), String(v)]));
  return { status, headers: { get: (name) => lower[name.toLowerCase()] ?? null }, text: async () => text };
}

export const ok = (data) => response(200, { data, traceId: 't' });
export const apiError = (status, code, message = `${code} message`) =>
  response(status, { error: { code, message, details: {} }, traceId: 't' });

/**
 * Runs the Action with the given inputs and responses.
 * Each item of `responses` is a fake Response, an Error to throw, or a function (request) => Response.
 */
export async function runAction({ inputs = {}, responses = [], spec = SPEC, specName = 'openapi.yaml' } = {}) {
  const workspace = mkdtempSync(join(tmpdir(), 'cartogra-action-'));
  if (spec !== null) writeFileSync(join(workspace, specName), spec);
  const outputFile = join(workspace, 'github-output');
  const summaryFile = join(workspace, 'step-summary');

  const env = {
    GITHUB_WORKSPACE: workspace,
    GITHUB_OUTPUT: outputFile,
    GITHUB_STEP_SUMMARY: summaryFile,
    'INPUT_API-KEY': 'ck_test',
    INPUT_SERVICE: 'ledger-service',
    INPUT_SPEC: specName,
    INPUT_URL: 'https://cartogra.example.com',
    'INPUT_FAIL-ON-BREAKING': 'true',
  };
  for (const [name, value] of Object.entries(inputs)) env[`INPUT_${name.toUpperCase()}`] = value;

  const requests = [];
  const sleeps = [];
  let stdout = '';
  let clock = 0;
  const queue = [...responses];

  const fetch = async (url, init) => {
    requests.push({ url, init, at: clock });
    const next = queue.length > 1 ? queue.shift() : queue[0];
    if (next === undefined) throw new Error('fake fetch: no response left');
    const value = typeof next === 'function' ? next({ url, init }) : next;
    if (value instanceof Error) throw value;
    return value;
  };
  const sleep = async (ms) => { sleeps.push(ms); clock += ms; };

  const exitCode = await run({
    env,
    fetch,
    readFile: (path) => readFileSync(path, 'utf8'),
    stdout: (text) => { stdout += text; },
    sleep,
  });

  return {
    exitCode,
    stdout,
    requests,
    sleeps,
    output: existsSync(outputFile) ? readFileSync(outputFile, 'utf8') : '',
    summary: existsSync(summaryFile) ? readFileSync(summaryFile, 'utf8') : '',
  };
}

export function outputs(text) {
  return Object.fromEntries(text.split('\n').filter(Boolean).map((l) => l.split('=')));
}

export function checkResult({ isBreaking = false, changes = [], consumers = [], baselineVersion = 3 } = {}) {
  return { isBreaking, baselineVersion, changes, affectedConsumers: consumers };
}

export function change(rule, breaking = true) {
  return { rule, breaking, direction: 'response', method: 'GET', path: '/accounts/{id}',
    location: 'response.200.balance', message: `${rule} message` };
}

export function consumer(name, i = 0) {
  return { serviceId: `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`, serviceName: name,
    dependencyTypes: ['http'], protocols: ['http'] };
}
