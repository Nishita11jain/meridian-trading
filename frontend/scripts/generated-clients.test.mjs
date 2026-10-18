/**
 * Checks on the generated API clients (SEC2-687). Run with: npm run test:scripts
 *
 * These need no Java: they read openapitools.json, the contracts, and the committed client
 * trees, and check the properties the story is about. They cannot replace a regeneration
 * (npm run generate, then git diff), but they fail when someone edits the generated tree by
 * hand, adds a file the generator did not write, unpins the generator, or changes a contract
 * without regenerating the clients.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const generatorConfig = JSON.parse(readFileSync(join(root, 'openapitools.json'), 'utf8'))['generator-cli'];
const entries = Object.entries(generatorConfig.generators ?? {});

/** Files in a client folder that the generator does not list in its own FILES manifest. */
const GENERATOR_CONTROL_FILES = new Set([
  '.openapi-generator-ignore',
  '.openapi-generator/FILES',
  '.openapi-generator/VERSION'
]);

function filesUnder(dir, base = dir) {
  const found = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) found.push(...filesUnder(full, base));
    else found.push(relative(base, full).split('\\').join('/'));
  }
  return found.sort();
}

function read(path) {
  return readFileSync(path, 'utf8');
}

/**
 * The contracts live one level up from this directory. Checked out without them (e.g. this
 * `frontend/` folder copied on its own), every test below that reads a contract would
 * otherwise fail with a raw ENOENT pointing at a path with no explanation. This gives one
 * clear reason instead, so a reviewer can tell "contracts not found" apart from "the client
 * is genuinely stale".
 */
function readContract(inputSpec) {
  const path = resolve(root, inputSpec);
  assert.ok(existsSync(path), `contracts not found at ${path} — check out this repository with contracts/ alongside frontend/, not frontend/ alone`);
  return read(path);
}

/** The top-level names under `components: schemas:` (plain line scan, no YAML library needed). */
function schemaNames(contractText) {
  const lines = contractText.split(/\r?\n/);
  const start = lines.findIndex((line) => /^ {2}schemas:\s*$/.test(line));
  assert.notEqual(start, -1, 'the contract has no components.schemas block');
  const names = [];
  for (const line of lines.slice(start + 1)) {
    if (/^ {2}\S/.test(line) || /^\S/.test(line)) break;
    const match = /^ {4}([A-Za-z0-9_]+):\s*$/.exec(line);
    if (match) names.push(match[1]);
  }
  return names;
}

function operationIds(contractText) {
  return [...contractText.matchAll(/^\s+operationId:\s*([A-Za-z0-9_]+)\s*$/gm)].map((match) => match[1]);
}

const lowerCamel = (name) => name.charAt(0).toLowerCase() + name.slice(1);

test('the generator version is pinned to one exact version', () => {
  assert.match(generatorConfig.version, /^\d+\.\d+\.\d+$/, 'a range or tag would let two people generate different output');
});

test('there is one entry per contract, each writing into its own subdirectory of the clients tree', () => {
  const inputs = entries.map(([, entry]) => entry.inputSpec).sort();
  assert.deepEqual(inputs, [
    '../contracts/api-schemas/advice-api.yaml',
    '../contracts/api-schemas/auth-api.yaml',
    '../contracts/api-schemas/notifications-api.yaml',
    '../contracts/api-schemas/portfolio-api.yaml',
    '../contracts/api-schemas/preferences-api.yaml',
    '../contracts/api-schemas/strategies-api.yaml',
    '../contracts/api-schemas/trade-api.yaml',
    '../contracts/api-schemas/watchlists-api.yaml'
  ]);

  const outputs = entries.map(([, entry]) => entry.output);
  assert.equal(new Set(outputs).size, outputs.length, 'two entries write to the same folder');
  for (const output of outputs) {
    assert.ok(output.startsWith('src/app/clients/'), `${output} is outside the clients tree`);
    assert.ok(existsSync(join(root, output)), `${output} does not exist: run npm run generate`);
  }

  for (const [, entry] of entries) {
    assert.ok(existsSync(resolve(root, entry.inputSpec)), `${entry.inputSpec} is not in the repository`);
  }
});

test('validation is skipped knowingly, for the contracts only', () => {
  for (const [name, entry] of entries) {
    assert.equal(entry.skipValidateSpec, true, `${name} must set skipValidateSpec (the contracts carry no info.license.identifier)`);
  }
});

for (const [name, entry] of entries) {
  const clientDir = join(root, entry.output);

  test(`${name}: every file in the tree was written by the generator`, () => {
    const manifest = read(join(clientDir, '.openapi-generator', 'FILES'))
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
    const onDisk = filesUnder(clientDir);

    const missing = manifest.filter((file) => !onDisk.includes(file));
    assert.deepEqual(missing, [], `listed by the generator but missing on disk (deleted by hand?): ${missing.join(', ')}`);

    const unknown = onDisk.filter((file) => !manifest.includes(file) && !GENERATOR_CONTROL_FILES.has(file));
    assert.deepEqual(unknown, [], `not written by the generator (hand-written file in machine output?): ${unknown.join(', ')}`);
  });

  test(`${name}: the recorded generator version is the pinned one`, () => {
    assert.equal(read(join(clientDir, '.openapi-generator', 'VERSION')).trim(), generatorConfig.version);
  });

  test(`${name}: no NgModule and no barrel are generated`, () => {
    const files = filesUnder(clientDir);
    assert.equal(files.includes('api.module.ts'), false, 'api.module.ts declares an NgModule');
    assert.equal(files.includes('index.ts'), false, 'index.ts re-exports the NgModule');

    const ignore = read(join(clientDir, '.openapi-generator-ignore'));
    assert.match(ignore, /^api\.module\.ts\s*$/m, 'api.module.ts must be in .openapi-generator-ignore or regeneration brings it back');
    assert.match(ignore, /^index\.ts\s*$/m, 'index.ts must be in .openapi-generator-ignore or regeneration brings it back');

    for (const file of files.filter((f) => f.endsWith('.ts'))) {
      assert.equal(/@NgModule/.test(read(join(clientDir, file))), false, `${file} declares an NgModule`);
    }
  });

  test(`${name}: the client is not stale against its contract`, () => {
    const contract = readContract(entry.inputSpec);
    const modelFiles = new Set(readdirSync(join(clientDir, 'model')));

    for (const schema of schemaNames(contract)) {
      assert.ok(
        modelFiles.has(`${lowerCamel(schema)}.ts`),
        `the contract defines ${schema} but the client has no ${lowerCamel(schema)}.ts: run npm run generate`
      );
    }

    const apiSource = readdirSync(join(clientDir, 'api'))
      .filter((file) => file.endsWith('.service.ts'))
      .map((file) => read(join(clientDir, 'api', file)))
      .join('\n');
    for (const operation of operationIds(contract)) {
      assert.ok(
        new RegExp(`public ${operation}\\(`).test(apiSource),
        `the contract has operation ${operation} but the client has no such method: run npm run generate`
      );
    }
  });
}

test('the error-code enums in the clients match the codes in the contracts', () => {
  for (const [name, entry] of entries) {
    const contract = readContract(entry.inputSpec);
    const contractCodes = [...contract.matchAll(/errorCode:\s*\n\s+type:\s*string\s*\n\s+enum:\s*\[([^\]]+)\]/g)]
      .flatMap((match) => match[1].split(',').map((code) => code.trim()))
      .sort();
    assert.ok(contractCodes.length > 0, `found no errorCode enum in ${entry.inputSpec}`);

    const generated = read(join(root, entry.output, 'model', 'errorResponse.ts'));
    const generatedCodes = [...generated.matchAll(/'([A-Z]+-\d{3})'/g)].map((match) => match[1]).sort();

    assert.deepEqual(generatedCodes, contractCodes, `${name}: client error codes differ from the contract: run npm run generate`);
  }
});

test('nothing outside the generated tree imports a client through a folder barrel', () => {
  const offenders = [];
  const scan = (dir) => {
    for (const item of readdirSync(dir)) {
      const full = join(dir, item);
      if (statSync(full).isDirectory()) {
        if (full !== join(root, 'src', 'app', 'clients')) scan(full);
      } else if (full.endsWith('.ts') && /from\s+['"][^'"]*clients\/(?:auth|trade)-client['"]/.test(read(full))) {
        offenders.push(relative(root, full));
      }
    }
  };
  scan(join(root, 'src'));

  assert.deepEqual(offenders, [], 'import from the specific client file, not the folder');
});

test('something outside the generated tree imports the clients', () => {
  const importers = [];
  const scan = (dir) => {
    for (const item of readdirSync(dir)) {
      const full = join(dir, item);
      if (statSync(full).isDirectory()) {
        if (full !== join(root, 'src', 'app', 'clients')) scan(full);
      } else if (full.endsWith('.ts') && !full.endsWith('.spec.ts') && /clients\/(?:auth|trade)-client\//.test(read(full))) {
        importers.push(relative(root, full));
      }
    }
  };
  scan(join(root, 'src'));

  assert.ok(importers.length > 0, 'a generated client that nothing imports is a build step, not a client');
});
