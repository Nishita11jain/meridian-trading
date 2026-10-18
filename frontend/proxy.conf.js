// Dev-server proxy for `ng serve`. Targets come from the root .env (AUTH_BASE_URL,
// TRADE_API_BASE_URL); real environment variables win. There are no fallback URLs.
const fs = require('fs');
const path = require('path');

const envFile = path.resolve(__dirname, '..', '.env');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].trim();
  }
}

for (const name of ['AUTH_BASE_URL', 'TRADE_API_BASE_URL']) {
  if (!process.env[name]) throw new Error(`${name} is not set; copy .env.example to .env at the repository root`);
}

module.exports = {
  '/auth': { target: process.env.AUTH_BASE_URL, changeOrigin: false, secure: false },
  '/api': { target: process.env.TRADE_API_BASE_URL, changeOrigin: false, secure: false },
};
