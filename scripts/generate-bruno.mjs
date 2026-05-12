#!/usr/bin/env node
/**
 * Generates a Bruno collection from an OpenAPI 3.x YAML spec.
 *
 * Usage:
 *   node scripts/generate-bruno.mjs <spec-path> <output-dir>
 *
 * Example:
 *   node scripts/generate-bruno.mjs docs/api/registry.openapi.yaml bruno/registry
 *
 * Requires: js-yaml  (npm install -g js-yaml  OR  npx --yes js-yaml)
 * Install once in the repo: npm install --save-dev js-yaml  (root package.json)
 */

import { readFileSync, mkdirSync, writeFileSync, existsSync } from "node:fs";
import { join, dirname, basename } from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

// ── resolve js-yaml from root node_modules or global ─────────────────────────
let yaml;
try {
  yaml = require(join(__dirname, "..", "node_modules", "js-yaml"));
} catch {
  try {
    yaml = require("js-yaml");
  } catch {
    console.error(
      "js-yaml not found. Run: npm install --save-dev js-yaml  (at repo root)"
    );
    process.exit(1);
  }
}

// ── args ──────────────────────────────────────────────────────────────────────
const [, , specPath, outputDir] = process.argv;
if (!specPath || !outputDir) {
  console.error(
    "Usage: node scripts/generate-bruno.mjs <spec.yaml> <output-dir>"
  );
  process.exit(1);
}

const spec = yaml.load(readFileSync(specPath, "utf8"));
const collectionName =
  spec.info?.title?.replace(/\s+/g, "-") ?? basename(specPath, ".yaml");

// ── helpers ───────────────────────────────────────────────────────────────────

function mkdir(dir) {
  mkdirSync(dir, { recursive: true });
}

function write(filePath, content) {
  mkdir(dirname(filePath));
  writeFileSync(filePath, content, "utf8");
  console.log("  wrote", filePath);
}

function slugify(str) {
  return str
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

/** Detect auth type from the operation security array + spec securitySchemes. */
function resolveAuth(operation, spec) {
  const schemes = spec.components?.securitySchemes ?? {};
  const secEntries =
    operation.security ?? spec.security ?? [];

  for (const entry of secEntries) {
    const [name] = Object.keys(entry);
    if (!name) continue;
    const scheme = schemes[name];
    if (!scheme) continue;
    if (scheme.type === "http" && scheme.scheme === "bearer") return "bearer";
    if (scheme.type === "apiKey" && scheme.in === "header") {
      return { apiKey: scheme.name };
    }
    if (scheme.type === "apiKey" && scheme.in === "cookie") return "cookie";
  }
  return "none";
}

/** Produce a minimal example value for a schema property. */
function exampleValue(schema) {
  if (!schema) return "";
  if (schema.example !== undefined) return schema.example;
  if (schema.default !== undefined) return schema.default;
  if (schema.enum) return schema.enum[0];
  switch (schema.type) {
    case "string":
      return schema.format === "uuid"
        ? "00000000-0000-0000-0000-000000000001"
        : schema.format === "uri"
        ? "https://example.com"
        : "string";
    case "integer":
      return schema.minimum ?? 0;
    case "number":
      return 0.0;
    case "boolean":
      return true;
    case "array":
      return [];
    case "object":
      return {};
    default:
      return "";
  }
}

/** Build a sample JSON body from a request body schema (shallow). */
function buildSampleBody(requestBody, spec) {
  const content = requestBody?.content?.["application/json"];
  if (!content?.schema) return null;

  let schema = content.schema;
  if (schema.$ref) {
    schema = resolveRef(schema.$ref, spec);
  }

  if (!schema?.properties) return {};

  const body = {};
  for (const [key, propSchema] of Object.entries(schema.properties)) {
    let resolved = propSchema;
    if (propSchema.$ref) resolved = resolveRef(propSchema.$ref, spec);
    body[key] = exampleValue(resolved);
  }
  // only include required fields
  const required = schema.required ?? [];
  if (required.length > 0) {
    const filtered = {};
    for (const k of required) {
      if (body[k] !== undefined) filtered[k] = body[k];
    }
    return filtered;
  }
  return body;
}

function resolveRef(ref, spec) {
  const parts = ref.replace(/^#\//, "").split("/");
  let node = spec;
  for (const part of parts) {
    node = node[part];
    if (!node) return null;
  }
  return node;
}

/** Standard envelope assertions for enveloped responses. */
const ENVELOPE_TESTS = `
  test("response envelope present", function() {
    expect(res.body).to.have.property("data");
    expect(res.body).to.have.property("traceId");
  });

  test("traceId is 32 lowercase hex", function() {
    expect(res.body.traceId).to.match(/^[0-9a-f]{32}$/);
  });

  test("X-Trace-Id header matches body traceId", function() {
    expect(res.headers["x-trace-id"]).to.equal(res.body.traceId);
  });`.trimStart();

/** Assertions for non-enveloped responses (e.g. CI endpoints). */
const TRACE_HEADER_TEST = `
  test("X-Trace-Id header is present", function() {
    expect(res.headers["x-trace-id"]).to.match(/^[0-9a-f]{32}$/);
  });`.trimStart();

/** Determine if an endpoint is enveloped by checking if it's NOT a webhook/CI raw endpoint. */
function isEnveloped(operation) {
  const desc = (operation.description ?? "").toLowerCase();
  return !desc.includes("not wrapped") && !desc.includes("flat result");
}

/** Generate the .bru file content for one operation. */
function generateBru(method, path, operation, spec, seq) {
  const name = operation.summary ?? operation.operationId ?? `${method} ${path}`;
  const auth = resolveAuth(operation, spec);
  const hasBody = !!operation.requestBody;
  const body = hasBody ? buildSampleBody(operation.requestBody, spec) : null;

  const bruPath = path.replace(/{([^}]+)}/g, "{{$1}}");

  // ── auth block ────────────────────────────────────────────────────────────
  let authBlock = "";
  let headersBlock = "";

  if (auth === "bearer") {
    authBlock = `\nauth:bearer {\n  token: {{authToken}}\n}`;
    headersBlock = `\nheaders {\n  X-Tenant-Id: {{tenantId}}\n}`;
  } else if (typeof auth === "object" && auth.apiKey) {
    headersBlock = `\nheaders {\n  ${auth.apiKey}: {{apiKey}}\n}`;
  }

  // ── body block ────────────────────────────────────────────────────────────
  let bodyBlock = "";
  if (hasBody && body !== null) {
    bodyBlock = `\nbody:json {\n  ${JSON.stringify(body, null, 2).replace(/\n/g, "\n  ")}\n}`;
  }

  // ── query params ──────────────────────────────────────────────────────────
  const queryParams = (operation.parameters ?? []).filter(
    (p) => p.in === "query"
  );
  let paramsBlock = "";
  if (queryParams.length > 0) {
    const lines = queryParams
      .map((p) => `  ${p.name}: ${p.schema?.default ?? ""}`)
      .join("\n");
    paramsBlock = `\nparams:query {\n${lines}\n}`;
  }

  // ── determine expected success status ────────────────────────────────────
  const successStatus = Object.keys(operation.responses ?? {})
    .map(Number)
    .filter((s) => s >= 200 && s < 300)[0] ?? 200;

  const authStr =
    auth === "bearer"
      ? "bearer"
      : auth === "cookie"
      ? "none"
      : "none";

  const enveloped = isEnveloped(operation);

  // ── tests block ───────────────────────────────────────────────────────────
  let tests = `\ntests {\n  test("status is ${successStatus}", function() {\n    expect(res.status).to.equal(${successStatus});\n  });\n`;
  if (successStatus !== 204) {
    tests += "\n  " + (enveloped ? ENVELOPE_TESTS : TRACE_HEADER_TEST) + "\n";
  } else {
    tests += "\n  " + TRACE_HEADER_TEST + "\n";
  }
  tests += "}";

  const bodyType =
    hasBody ? "json" : "none";

  return `meta {
  name: ${name}
  type: http
  seq: ${seq}
}

${method.toLowerCase()} {
  url: {{baseUrl}}${bruPath}
  body: ${bodyType}
  auth: ${authStr}
}${authBlock}${headersBlock}${bodyBlock}${paramsBlock}${tests}
`;
}

// ── main ──────────────────────────────────────────────────────────────────────

mkdir(outputDir);

// bruno.json
write(
  join(outputDir, "bruno.json"),
  JSON.stringify({ version: "1", name: collectionName, type: "collection", ignore: [] }, null, 2) + "\n"
);

// environments
const servers = spec.servers ?? [];
for (const server of servers) {
  const envName = slugify(server.description ?? server.url);
  write(
    join(outputDir, "environments", `${envName}.bru`),
    `vars {\n  baseUrl: ${server.url}\n  tenantId: 00000000-0000-0000-0000-000000000001\n}\n\nvars:secret [\n  authToken,\n  apiKey\n]\n`
  );
}

// request files grouped by tag
const tagCounters = {};

for (const [path, pathItem] of Object.entries(spec.paths ?? {})) {
  const methods = ["get", "post", "put", "patch", "delete", "head", "options"];
  for (const method of methods) {
    const operation = pathItem[method];
    if (!operation) continue;

    const tag = slugify((operation.tags ?? ["untagged"])[0]);
    tagCounters[tag] = (tagCounters[tag] ?? 0) + 1;
    const seq = tagCounters[tag];
    const filename = `${String(seq).padStart(2, "0")}-${slugify(operation.operationId ?? `${method}-${path}`)}.bru`;

    write(
      join(outputDir, tag, filename),
      generateBru(method, path, operation, spec, seq)
    );
  }
}

console.log(`\nCollection "${collectionName}" generated in ${outputDir}`);
console.log("Open the folder in Bruno desktop or run:  npx @usebruno/cli run " + outputDir);
