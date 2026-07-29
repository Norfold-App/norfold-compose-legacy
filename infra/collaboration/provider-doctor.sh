#!/usr/bin/env bash
set -u
REPO="${1:-/home/sheikh/GitHub/Libre-Notes}"; FAIL=0
ok(){ printf '  [OK]   %s\n' "$1";}; warn(){ printf '  [WARN] %s\n' "$1";}; bad(){ printf '  [FAIL] %s\n' "$1"; FAIL=1;}
echo "Norfold collaboration provider doctor";echo "Repo: $REPO";echo
for cmd in git node npm npx curl python3; do command -v "$cmd" >/dev/null 2>&1 && ok "$cmd: $(command -v "$cmd")" || bad "$cmd missing"; done
[ -d "$REPO/.git" ] && ok "Norfold Git checkout exists" || bad "Norfold Git checkout not found"
command -v claude >/dev/null 2>&1 && ok "Claude Code installed" || warn "Claude Code command not found"
command -v codex >/dev/null 2>&1 && ok "Codex installed" || warn "Codex command not found"
[ -f "$REPO/infra/collaboration/cloudflare/wrangler.jsonc" ] && ok "Cloudflare scaffold installed" || warn "Cloudflare scaffold not copied into repo"
if command -v ably >/dev/null 2>&1; then ok "Ably CLI installed"; ably status >/dev/null 2>&1 && ok "Ably CLI authenticated/usable" || warn "Ably CLI needs init/login"; else warn "Ably CLI not installed; run npx @ably/cli init"; fi
[ -n "${NORFOLD_CF_BENCHMARK_SECRET:-}" ] && ok "Cloudflare benchmark secret loaded" || warn "NORFOLD_CF_BENCHMARK_SECRET not loaded"
JWKS='https://libvhznygsoppyfhucpt.supabase.co/auth/v1/.well-known/jwks.json'
if curl -fsSL --max-time 10 "$JWKS" | python3 -c 'import json,sys;d=json.load(sys.stdin);assert isinstance(d.get("keys"),list);print(len(d["keys"]))' >/tmp/norfold_jwks_count 2>/dev/null; then count="$(cat /tmp/norfold_jwks_count)"; ok "Supabase JWKS reachable (keys=$count)"; [ "$count" = 0 ] && warn "JWKS has no public keys; inspect legacy signing before production edge auth"; else warn "Could not verify Supabase JWKS"; fi
rm -f /tmp/norfold_jwks_count
echo;[ "$FAIL" -eq 0 ] && echo "Doctor completed without hard failures." || echo "Doctor found hard failures.";exit "$FAIL"
