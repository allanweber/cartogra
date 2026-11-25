// Cartogra Contract Check: sends a spec to POST /api/v1/contracts/check and fails the step on a breaking change.
// No dependencies. The exported run(...) is the test seam; the bottom of the file calls it with the real values.
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const MAX_SPEC_CHARS = 1_048_576;
export const MAX_TRIES = 3;
export const TRY_TIMEOUT_MS = 30_000;
const MAX_RETRY_AFTER_S = 30;
const BACKOFF_MS = [2_000, 4_000];
const DEFAULT_URL = 'https://cartogra.dev';
const CHECK_PATH = '/api/v1/contracts/check';

class StepError extends Error {}

/**
 * Runs the check and returns the exit code (0 or 1).
 * @param {object} deps
 * @param {Record<string, string | undefined>} deps.env the process env, with the INPUT_* values of the step
 * @param {typeof fetch} deps.fetch
 * @param {(path: string) => string} deps.readFile reads a file as UTF-8 text
 * @param {(text: string) => void} deps.stdout
 * @param {(ms: number) => Promise<void>} deps.sleep
 */
export async function run({ env, fetch, readFile, stdout, sleep }) {
  const apiKey = input(env, 'api-key');
  // The mask must come before any other output, so that no line can show the key.
  if (apiKey) stdout(`::add-mask::${apiKey}\n`);
  const out = (text) => stdout(apiKey ? text.replaceAll(apiKey, '***') : text);

  try {
    const config = readConfig(env, readFile);
    const result = await check(config, fetch, sleep);
    return report(config, result, env, out);
  } catch (e) {
    const message = e instanceof StepError ? e.message : `Unexpected error: ${e?.message ?? e}`;
    out(`::error title=Cartogra contract check::${escapeCommand(message)}\n`);
    return 1;
  }
}

function input(env, name) {
  return (env[`INPUT_${name.toUpperCase()}`] ?? '').trim();
}

function required(env, name) {
  const value = input(env, name);
  if (!value) throw new StepError(`Input required: ${name}`);
  return value;
}

function readConfig(env, readFile) {
  const apiKey = required(env, 'api-key');
  const service = required(env, 'service');
  const specPath = required(env, 'spec');

  const failOnBreakingRaw = input(env, 'fail-on-breaking') || 'true';
  if (failOnBreakingRaw !== 'true' && failOnBreakingRaw !== 'false') {
    throw new StepError(`Input fail-on-breaking must be "true" or "false", not "${failOnBreakingRaw}"`);
  }

  const endpoint = checkUrl(input(env, 'url') || DEFAULT_URL);
  const spec = readSpec(env, readFile, specPath);
  return { apiKey, service, spec, endpoint, failOnBreaking: failOnBreakingRaw === 'true' };
}

function checkUrl(raw) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new StepError(`Input url is not a valid URL: ${raw}`);
  }
  const local = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) {
    throw new StepError(`Input url must use https (http is accepted only for localhost and 127.0.0.1): ${raw}`);
  }
  return raw.replace(/\/+$/, '') + CHECK_PATH;
}

function readSpec(env, readFile, specPath) {
  const fullPath = resolve(env.GITHUB_WORKSPACE || process.cwd(), specPath);
  let spec;
  try {
    spec = readFile(fullPath);
  } catch (e) {
    if (e?.code === 'ENOENT') throw new StepError(`Spec file not found: ${specPath}`);
    throw new StepError(`Cannot read the spec file ${specPath}: ${e?.message ?? e}`);
  }
  if (spec.length > MAX_SPEC_CHARS) {
    throw new StepError(
      `Spec file ${specPath} has ${spec.length} characters, more than the 1 MiB limit (${MAX_SPEC_CHARS} characters)`);
  }
  return spec;
}

/** Sends the request. It retries on 429, 5xx, a network error, and a timeout, with a maximum of 3 tries. Returns { status, body } of the last response that does not retry. */
async function check(config, fetch, sleep) {
  const body = JSON.stringify({ service: config.service, spec: config.spec });
  let failure = '';
  for (let attempt = 1; attempt <= MAX_TRIES; attempt++) {
    const last = attempt === MAX_TRIES;
    let wait;
    try {
      // The timeout signal also covers the read of the body, so the read is inside the try too.
      const response = await fetch(config.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Cartogra-Api-Key': config.apiKey },
        body,
        signal: AbortSignal.timeout(TRY_TIMEOUT_MS),
        // fetch keeps the key header on a redirect to a different host, and it follows https to http. Do not follow.
        redirect: 'manual',
      });
      const status = response.status;
      if (status >= 300 && status < 400) {
        throw new StepError(`Cartogra contract check failed: HTTP ${status}, a redirect to ${response.headers?.get?.('location') ?? 'an unknown location'}.`
          + ' The Action does not follow redirects. Set url to the final address');
      }
      const retries = status === 429 || status >= 500;
      if (!retries || last) return { status, body: await parseJson(response) };
      wait = status === 429 ? retryAfterMs(response) : BACKOFF_MS[attempt - 1];
    } catch (e) {
      if (e instanceof StepError) throw e;
      const timeout = e?.name === 'TimeoutError' || e?.name === 'AbortError';
      failure = timeout ? `Timeout after ${TRY_TIMEOUT_MS / 1000} s` : `Network error: ${e?.message ?? e}`;
      if (last) break;
      wait = BACKOFF_MS[attempt - 1];
    }
    await sleep(wait);
  }
  throw new StepError(`Cartogra contract check failed after ${MAX_TRIES} tries: ${failure}`);
}

function retryAfterMs(response) {
  const seconds = Number.parseInt(response.headers?.get?.('retry-after') ?? '', 10);
  if (!Number.isFinite(seconds) || seconds < 0) return BACKOFF_MS[0];
  return Math.min(seconds, MAX_RETRY_AFTER_S) * 1000;
}

async function parseJson(response) {
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function report(config, { status, body }, env, out) {
  if (status === 200 && body?.data && typeof body.data.isBreaking === 'boolean') {
    return reportResult(config, body.data, env, out);
  }

  const code = body?.error?.code;
  if (status === 404 && code === 'BASELINE_NOT_FOUND') {
    writeOutputs(env, false, 0);
    out(`::warning title=Cartogra contract check::${escapeCommand(
      `No baseline for ${config.service} in Cartogra yet, so nothing can break. The check passes.`)}\n`);
    return 0;
  }

  if (body === undefined) {
    throw new StepError(`Cartogra contract check failed: HTTP ${status}, the response is not JSON`);
  }
  if (status === 200) {
    throw new StepError('Cartogra contract check failed: HTTP 200, the response has no check result');
  }
  const message = body?.error?.message;
  throw new StepError(`Cartogra contract check failed: HTTP ${status} ${code ?? 'UNKNOWN'}${message ? `: ${message}` : ''}`);
}

function reportResult(config, data, env, out) {
  const changes = Array.isArray(data.changes) ? data.changes : [];
  const consumers = Array.isArray(data.affectedConsumers) ? data.affectedConsumers : [];
  writeOutputs(env, data.isBreaking, changes.length);
  // The values come from the server. Remove the key from them before the summary escapes them.
  const safe = (value) => (typeof value === 'string' ? value.replaceAll(config.apiKey, '***') : value);
  const clean = (item) => (item && typeof item === 'object'
    ? Object.fromEntries(Object.entries(item).map(([k, v]) => [k, safe(v)])) : item);
  writeSummary(env, summary(config.service, clean(data), changes.map(clean), consumers.map(clean)));

  if (!data.isBreaking) {
    out(`Cartogra contract check: ${config.service} is compatible (${changes.length} changes).\n`);
    return 0;
  }

  const breaking = changes.filter((c) => c?.breaking).length;
  const text = `${config.service} has ${breaking} breaking ${breaking === 1 ? 'change' : 'changes'}`
    + ` and ${consumers.length} affected ${consumers.length === 1 ? 'consumer' : 'consumers'}`;
  if (config.failOnBreaking) {
    out(`::error title=Cartogra contract check::${escapeCommand(text)}\n`);
    return 1;
  }
  out(`::warning title=Cartogra contract check::${escapeCommand(`${text}. fail-on-breaking is false, so the step passes.`)}\n`);
  return 0;
}

function summary(service, data, changes, consumers) {
  const lines = [
    '## Cartogra contract check',
    '',
    `**Service:** ${escapeMarkdown(service)}  `,
    `**Baseline version:** ${escapeMarkdown(data.baselineVersion)}  `,
    `**Result:** ${data.isBreaking ? 'Breaking' : 'Compatible'}`,
    '',
  ];

  if (changes.length === 0) {
    lines.push('No changes', '');
  } else {
    lines.push('| Rule | Severity | Location |', '| --- | --- | --- |');
    for (const c of changes) {
      const location = [c?.method, c?.path, c?.location].filter(Boolean).join(' ');
      lines.push(`| ${escapeMarkdown(c?.rule)} | ${c?.breaking ? 'breaking' : 'non-breaking'} | ${escapeMarkdown(location)} |`);
    }
    lines.push('');
  }

  const names = consumers.map((c) => escapeMarkdown(c?.serviceName || c?.serviceId));
  lines.push(`**Affected consumers (${consumers.length}):** ${names.length ? names.join(', ') : 'none'}`, '');
  return lines.join('\n');
}

/** Escapes a response value for a Markdown table cell: no HTML, no table break, no code span, no link, no emphasis. */
export function escapeMarkdown(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('\\', '&#92;')
    .replaceAll('[', '&#91;')
    .replaceAll(']', '&#93;')
    .replaceAll('(', '&#40;')
    .replaceAll(')', '&#41;')
    .replaceAll('*', '&#42;')
    .replaceAll('_', '&#95;')
    .replaceAll('~', '&#126;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('|', '&#124;')
    .replaceAll('`', '&#96;')
    .replace(/[\r\n]+/g, ' ');
}

/** Escapes the data of a workflow command (::error::, ::warning::). */
function escapeCommand(value) {
  return String(value).replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');
}

function writeOutputs(env, isBreaking, changesCount) {
  if (!env.GITHUB_OUTPUT) return;
  appendFileSync(env.GITHUB_OUTPUT, `is-breaking=${isBreaking}\nchanges-count=${changesCount}\n`);
}

function writeSummary(env, markdown) {
  if (!env.GITHUB_STEP_SUMMARY) return;
  appendFileSync(env.GITHUB_STEP_SUMMARY, markdown + '\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await run({
    env: process.env,
    fetch: globalThis.fetch,
    readFile: (path) => readFileSync(path, 'utf8'),
    stdout: (text) => process.stdout.write(text),
    sleep: (ms) => new Promise((done) => setTimeout(done, ms)),
  });
}
