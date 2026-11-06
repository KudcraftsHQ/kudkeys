#!/usr/bin/env bash
# Conductor worktree provisioning for kudkeys.
#
# Run by `conductor adopt` / `conductor worktree create` with the worktree as CWD, after conductor has allocated
# ports and cloned the dev database (a copy of production keys-db).
#
# Conductor gives us:
#   CONDUCTOR_WORKSPACE_NAME  the worktree's city name (bangkok-2, …)
#   CONDUCTOR_ROOT_PATH       the root checkout (~/Projects/kudkeys)
#   CONDUCTOR_PORT            first of two ports: app (Vite, proxies /api), server (Hono on Bun)
#   CONDUCTOR_DB_NAME         dev_<city>, the per-worktree database
#   CONDUCTOR_DB_URL          full URL to it
#
# The android app needs no dev server; this sets up the server and the web app only.
#
# Knobs: KUDKEYS_DEV_HOST (Tailscale host), KUDKEYS_DEV_SIGNIN_AS (who the dev web app signs everyone in as,
# default hammas.hamzah@gmail.com), KUDKEYS_DEV_GROQ_API_KEY (a Groq key for voice + text in this worktree;
# without one those features answer "not configured").
#
# NOTE: conductor reads conductor.json, and so this script, from the ROOT checkout, not from the worktree's branch.
# Changes here only take effect for new worktrees once they are on main.
set -euo pipefail

APP_PORT="${CONDUCTOR_PORT:-5181}"
API_PORT=$((APP_PORT + 1))
HOST="${KUDKEYS_DEV_HOST:-hammas-dev.tailaf13a.ts.net}"
ORIGIN="http://$HOST:$APP_PORT"

echo "=== kudkeys conductor setup: ${CONDUCTOR_WORKSPACE_NAME:-local} ==="
echo "Ports: app=$APP_PORT server=$API_PORT · $ORIGIN"
if [ -z "${CONDUCTOR_DB_URL:-}" ]; then
  echo "ERROR: conductor gave no CONDUCTOR_DB_URL. Check 'conductor database config' for kudkeys." >&2
  exit 1
fi

echo "[1/4] Installing dependencies..."
bun install

echo "[2/4] Preparing .env..."
if [ ! -f .env ]; then
  if [ -n "${CONDUCTOR_ROOT_PATH:-}" ] && [ -f "$CONDUCTOR_ROOT_PATH/.env" ] && [ "$PWD" != "$CONDUCTOR_ROOT_PATH" ]; then
    cp "$CONDUCTOR_ROOT_PATH/.env" .env
    echo "  copied .env from the root checkout"
  else
    cp .env.example .env
    echo "  created .env from .env.example"
  fi
  chmod 600 .env
fi
# set_env KEY VALUE: replace the line (commented or not), or append it
set_env() {
  local v="${2//\\/\\\\}"
  v="${v//&/\\&}"
  v="${v//|/\\|}"
  if grep -qE "^#? *$1=" .env; then
    sed -i -E "s|^#? *$1=.*|$1=\"$v\"|" .env
  else
    printf '%s="%s"\n' "$1" "$2" >> .env
  fi
}
# a secret of its own, once
if ! grep -qE '^BETTER_AUTH_SECRET="?[^" ]' .env; then
  set_env BETTER_AUTH_SECRET "$(openssl rand -base64 32)"
fi
# everything else follows this worktree's ports and database, every time
set_env DATABASE_URL "$CONDUCTOR_DB_URL"
set_env DATABASE_URL_POOLING "$CONDUCTOR_DB_URL"
set_env BETTER_AUTH_URL "$ORIGIN"
set_env KUDKEYS_ORIGIN "http://localhost:$APP_PORT,http://127.0.0.1:$APP_PORT"
set_env PORT "$API_PORT"
# dev only: every browser is signed in as this user (the server refuses it under NODE_ENV=production)
set_env KUDKEYS_DEV_AUTO_SIGNIN "${KUDKEYS_DEV_SIGNIN_AS:-hammas.hamzah@gmail.com}"
if [ -n "${KUDKEYS_DEV_GROQ_API_KEY:-}" ]; then
  set_env GROQ_API_KEY "$KUDKEYS_DEV_GROQ_API_KEY"
fi
echo "  database: ${CONDUCTOR_DB_NAME:-$CONDUCTOR_DB_URL}"

echo "[3/4] Generating Prisma client..."
bunx prisma generate >/dev/null && echo "  prisma client generated"

# The dev database is a clone of production, _prisma_migrations included, so only migrations added on this branch
# are outstanding. A branch that can't migrate still gets a usable worktree.
echo "[4/4] Applying branch migrations (if any)..."
if ! bunx prisma migrate deploy; then
  echo "  WARNING: 'prisma migrate deploy' failed; the schema may be stale." >&2
fi

echo ""
echo "Setup complete. Web app: $ORIGIN (signed in automatically)"
