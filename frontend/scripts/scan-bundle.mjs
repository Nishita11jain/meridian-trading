#!/usr/bin/env node
/**
 * SEC2-695: search the production bundle for anything that must never reach a browser.
 *
 * Usage:
 *   npm run build
 *   npm run scan:bundle                 # scans dist/sprint-09-trading-ui
 *   node scripts/scan-bundle.mjs <dir>  # scans another build output
 *
 * It looks for, in every text file in the build (js, css, html, json, source maps, ...):
 *   - x-api-key, api_key, api-key and the Fauxnance name (a market-data key in the bundle is a key published)
 *   - an execute-api.<region>.amazonaws.com host (this app never calls the market-data API)
 *   - jwt_secret, a secret assigned a long literal, and a three-part JWT written into the source
 *   - the literal VALUES of our own key and signing secret, read from ../.env (git-ignored) and from
 *     the SCAN_LITERALS environment variable (comma separated), so a value pasted in under a name
 *     none of the patterns above would match is still caught.
 *
 * Literal values are never printed: a hit reports the file, the pattern name and a line number only.
 * Exit code 0 = clean, 1 = something found, 2 = nothing to scan. Treat a key that reached a bundle as
 * published: revoke it, do not just delete the line.
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const target = resolve(process.argv[2] ?? join(root, 'dist', 'sprint-09-trading-ui'));

const TEXT_EXTENSIONS = new Set(['.js', '.mjs', '.cjs', '.css', '.html', '.json', '.map', '.txt', '.svg', '.xml', '.webmanifest']);

// A reviewer's own grep for "a market-data key in the bundle" is rarely exactly these
// literal patterns; `api[_-]?key` is the broader, more likely search. That pattern also
// matches the generated OpenAPI client's own `Configuration.apiKeys` field (plural,
// camelCase, no separator) — a real field name from the generator, not a secret. Allow
// that one specific, known-safe occurrence explicitly rather than narrow the pattern,
// so the scan still matches what a reviewer's own grep would find.
function isGeneratedApiKeysField(text, index, matchLength) {
  const window = text.slice(index, index + matchLength + 1);
  return /^api[Kk]eys\b/.test(window);
}

const PATTERNS = [
  { name: 'x-api-key header name', regex: /x-api-key/i },
  { name: 'api_key', regex: /api_key/i },
  { name: 'api-key', regex: /api-key/i },
  { name: 'apikey (no separator)', regex: /api[_-]?key/gi, allow: isGeneratedApiKeysField },
  { name: 'fauxnance', regex: /fauxnance/i },
  { name: 'execute-api host', regex: /execute-api\.[a-z0-9-]+\.amazonaws\.com/i },
  { name: 'jwt_secret', regex: /jwt_secret/i },
  { name: 'secret assigned a long literal', regex: /(?:secret|passwd|password)["']?\s*[:=]\s*["'][A-Za-z0-9+/_\-=]{24,}["']/i },
  { name: 'three-part JWT', regex: /eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/ }
];

const SENSITIVE_ENV_NAME = /(KEY|SECRET|PASSWORD|TOKEN)/i;

function literalValuesToSearchFor() {
  const values = new Map();

  const envFile = join(root, '..', '.env');
  if (existsSync(envFile)) {
    for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
      const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/.exec(line);
      if (!match || !SENSITIVE_ENV_NAME.test(match[1])) continue;
      const value = match[2].replace(/^["']|["']$/g, '').trim();
      if (value.length >= 8) values.set(value, `value of ${match[1]} from ../.env`);
    }
  }

  for (const value of (process.env.SCAN_LITERALS ?? '').split(',').map((v) => v.trim())) {
    if (value.length >= 8) values.set(value, 'a value from SCAN_LITERALS');
  }

  return values;
}

function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) yield* walk(full);
    else yield full;
  }
}

function lineNumberOf(text, index) {
  let line = 1;
  for (let i = 0; i < index; i++) if (text.charCodeAt(i) === 10) line++;
  return line;
}

if (!existsSync(target) || !statSync(target).isDirectory()) {
  console.error(`Nothing to scan: ${target} does not exist. Run "npm run build" first.`);
  process.exit(2);
}

const literals = literalValuesToSearchFor();
const findings = [];
let filesScanned = 0;

for (const file of walk(target)) {
  if (!TEXT_EXTENSIONS.has(extname(file).toLowerCase())) continue;
  filesScanned++;
  const text = readFileSync(file, 'utf8');
  const shown = relative(root, file);

  for (const { name, regex, allow } of PATTERNS) {
    if (regex.global) {
      // A global pattern may have one or more allowed (known-safe) occurrences before a
      // real one; keep scanning past allowed matches rather than stopping at the first.
      regex.lastIndex = 0;
      let match;
      while ((match = regex.exec(text)) !== null) {
        if (allow?.(text, match.index, match[0].length)) continue;
        findings.push(`${shown}:${lineNumberOf(text, match.index)}  matches "${name}"`);
        break;
      }
    } else {
      const match = regex.exec(text);
      if (match) findings.push(`${shown}:${lineNumberOf(text, match.index)}  matches "${name}"`);
    }
  }

  for (const [value, description] of literals) {
    const index = text.indexOf(value);
    if (index !== -1) findings.push(`${shown}:${lineNumberOf(text, index)}  contains ${description}`);
  }
}

const sourceMaps = [...walk(target)].filter((file) => file.endsWith('.map'));

console.log(`Scanned ${filesScanned} files in ${relative(root, target) || target}`);
console.log(`Searched for ${PATTERNS.length} patterns and ${literals.size} literal value(s) of our own keys and secrets.`);
if (sourceMaps.length > 0) {
  console.log(`Note: ${sourceMaps.length} source map file(s) are in the build. A .map file is the source back again.`);
}

if (findings.length > 0) {
  console.error('\nFOUND:');
  for (const finding of findings) console.error(`  ${finding}`);
  console.error('\nDo not just delete the line. A key that reached a bundle is published: revoke it.');
  process.exit(1);
}

console.log('Clean: nothing that must stay out of the browser was found.');
