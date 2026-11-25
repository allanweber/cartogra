import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { checkVersion } from '../scripts/check-version.mjs';

const script = fileURLToPath(new URL('../scripts/check-version.mjs', import.meta.url));
const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));

test('S-18: a tag with a different version fails', () => {
  assert.match(checkVersion('action-v1.0.1', '1.0.0'), /does not match version 1\.0\.0/);
});

test('S-18: a tag that is not action-vX.Y.Z fails', () => {
  for (const tag of ['v1.0.0', 'action-v1.0', 'action-v1.0.0-rc1', '', undefined]) {
    assert.match(checkVersion(tag, '1.0.0'), /is not action-vX\.Y\.Z/, String(tag));
  }
});

test('S-18: a matching tag passes', () => {
  assert.equal(checkVersion('action-v1.1.0', '1.1.0'), null);
});

test('S-18: the script exits 1 on a mismatch and 0 on the version of package.json', () => {
  assert.equal(spawnSync(process.execPath, [script, 'action-v0.0.0-x']).status, 1);
  assert.equal(spawnSync(process.execPath, [script, 'action-v99.0.0']).status, 1);
  assert.equal(spawnSync(process.execPath, [script, `action-v${version}`]).status, 0);
});
