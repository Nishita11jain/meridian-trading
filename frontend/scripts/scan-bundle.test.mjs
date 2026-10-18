/**
 * Tests for scan-bundle.mjs (SEC2-695). Run with: npm run test:scripts
 *
 * The scan is only worth trusting if it is known to fail when it should, so each test builds a
 * small fake "bundle" in a temporary folder, runs the real script against it in its own process,
 * and checks the exit code and what was reported.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = resolve(fileURLToPath(new URL('./scan-bundle.mjs', import.meta.url)));

/** Builds a fake bundle from { 'relative/path': 'contents' } and scans it. */
function scan(files, env = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'scan-bundle-test-'));
  try {
    for (const [name, contents] of Object.entries(files)) {
      const target = join(dir, name);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, contents);
    }
    const result = spawnSync(process.execPath, [script, dir], {
      encoding: 'utf8',
      env: { ...process.env, ...env }
    });
    return { status: result.status, output: `${result.stdout}${result.stderr}` };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('a bundle with nothing sensitive in it is reported clean', () => {
  const { status, output } = scan({
    'main.js': 'console.log("hello"); const base = "/api/v1";',
    'index.html': '<html><body>app</body></html>'
  });

  assert.equal(status, 0);
  assert.match(output, /Clean/);
});

const SHOULD_BE_FOUND = [
  ['an x-api-key header name', 'const h = { "X-API-KEY": "abc" };', 'x-api-key header name'],
  ['an api_key name', 'const api_key = "abc";', 'api_key'],
  ['an api-key name', 'fetch(url, { headers: { "api-key": k } });', 'api-key'],
  ['the market-data provider name', '// calls Fauxnance directly', 'fauxnance'],
  ['an execute-api host', 'const u = "https://abc123.execute-api.eu-west-2.amazonaws.com/v1";', 'execute-api host'],
  ['a jwt_secret name', 'const jwt_secret = "x";', 'jwt_secret'],
  ['a secret assigned a long literal', 'const client_secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";', 'secret assigned a long literal'],
  ['a three-part JWT', 'const t = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop";', 'three-part JWT']
];

for (const [label, contents, reported] of SHOULD_BE_FOUND) {
  test(`fails and names the pattern when the bundle contains ${label}`, () => {
    const { status, output } = scan({ 'main.js': contents });

    assert.equal(status, 1, output);
    assert.ok(output.includes(`matches "${reported}"`), `expected the report to name "${reported}":\n${output}`);
  });
}

test('finds a sensitive pattern in a nested folder and in a source map', () => {
  const nested = scan({ 'assets/deep/chunk.js': 'const api_key = "abc";' });
  const map = scan({ 'main.js.map': '{"sourcesContent":["const jwt_secret = 1"]}' });

  assert.equal(nested.status, 1);
  assert.equal(map.status, 1);
});

test('finds a literal value of our own secret pasted in under an innocent name, without printing it', () => {
  const secret = 'Zx9-literal-value-not-matching-any-pattern-7731';
  const { status, output } = scan({ 'main.js': `const greeting = "${secret}";` }, { SCAN_LITERALS: secret });

  assert.equal(status, 1, output);
  assert.match(output, /contains a value from SCAN_LITERALS/);
  assert.equal(output.includes(secret), false, 'the secret value must never be printed');
});

test('reports the file and line of a hit', () => {
  const { output } = scan({ 'main.js': 'const a = 1;\nconst b = 2;\nconst api_key = "abc";\n' });

  assert.match(output, /main\.js:3 {2}matches "api_key"/);
});

test('ignores files that are not text', () => {
  const { status } = scan({ 'logo.png': 'x-api-key api_key fauxnance' });

  assert.equal(status, 0);
});

test('warns that source maps are present but does not fail for that alone', () => {
  const { status, output } = scan({ 'main.js': 'console.log(1)', 'main.js.map': '{"version":3}' });

  assert.equal(status, 0);
  assert.match(output, /source map/i);
});

test('exits with 2 when there is no build to scan', () => {
  const missing = join(tmpdir(), 'scan-bundle-test-does-not-exist');
  const result = spawnSync(process.execPath, [script, missing], { encoding: 'utf8' });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /Nothing to scan/);
});
