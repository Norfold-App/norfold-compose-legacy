#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v node >/dev/null 2>&1 || { echo "Node.js is required. Install a supported Node release with nvm/mise, then rerun." >&2; exit 1; }
command -v npm >/dev/null 2>&1 || { echo "npm is required." >&2; exit 1; }
npm install
npx wrangler types
echo
echo "Cloudflare collaboration scaffold dependencies installed."
echo "Next: npx wrangler login && npx wrangler whoami"
echo "Then copy .dev.vars.example to .dev.vars, set the benchmark secret, and run npm run check && npm run dev."
