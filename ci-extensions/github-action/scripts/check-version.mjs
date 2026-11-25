// Usage: node scripts/check-version.mjs action-vX.Y.Z
// Exits 1 when the tag is not action-vX.Y.Z or its version is not equal to "version" in package.json.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TAG = /^action-v(\d+\.\d+\.\d+)$/;

/** Returns null when the tag matches the version, else the error text. */
export function checkVersion(tag, version) {
  const match = TAG.exec(tag ?? '');
  if (!match) return `The tag "${tag ?? ''}" is not action-vX.Y.Z`;
  if (match[1] !== version) return `The tag ${tag} does not match version ${version} in package.json`;
  return null;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const packageJson = fileURLToPath(new URL('../package.json', import.meta.url));
  const { version } = JSON.parse(readFileSync(packageJson, 'utf8'));
  const error = checkVersion(process.argv[2], version);
  if (error) {
    console.error(error);
    process.exitCode = 1;
  } else {
    console.log(`The tag ${process.argv[2]} matches version ${version}`);
  }
}
