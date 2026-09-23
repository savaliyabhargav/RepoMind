#!/usr/bin/env bash
# Starts a Gemma LLM server on a Kaggle GPU and points RepoMind at it.
#
#   kaggle/start.sh              start a session (default cap: 90 minutes)
#   kaggle/start.sh status       show GPU quota
#   MAX_MINUTES=30 kaggle/start.sh
#
# Needs a Kaggle API token (https://www.kaggle.com/settings -> API) in ~/.kaggle/access_token or the
# KAGGLE_API_TOKEN env var, and a phone-verified account (GPU + internet). Nothing secret is written to a tracked file:
# the tunnel URL and per-session key go to the git-ignored .env.local.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="${MODEL:-gemma4:e4b-it-qat}"
MAX_MINUTES="${MAX_MINUTES:-90}"
FALLBACK_ENDPOINT="${FALLBACK_ENDPOINT:-}"   # optional extra endpoint tried when Kaggle fails, e.g. http://host.docker.internal:11434/v1
WAIT_SECONDS="${WAIT_SECONDS:-900}"                                              # how long to wait for the tunnel URL
VENV="$ROOT/kaggle/.venv"

die() { echo "error: $*" >&2; exit 1; }
command -v python3 >/dev/null || die "python3 is required"
command -v curl >/dev/null || die "curl is required"

# ── Kaggle CLI (own venv, so nothing is installed system-wide) ───────────────
if [ -x "$VENV/bin/kaggle" ]; then
  KAGGLE="$VENV/bin/kaggle"
elif command -v kaggle >/dev/null; then
  KAGGLE="$(command -v kaggle)"
else
  echo "Installing the Kaggle CLI into $VENV ..."
  python3 -m venv "$VENV" && "$VENV/bin/pip" -q install kaggle
  KAGGLE="$VENV/bin/kaggle"
fi

if [ "${1:-}" = "status" ]; then
  exec "$KAGGLE" quota
fi

USERNAME="${KAGGLE_USERNAME:-$("$KAGGLE" config view 2>/dev/null | sed -n 's/^- username: //p')}"
[ -n "$USERNAME" ] && [ "$USERNAME" != "None" ] || die "Kaggle username unknown - set up a token or export KAGGLE_USERNAME"
SLUG="repomind-gemma-server"

"$KAGGLE" quota | sed -n '1,3p'
echo

# ── fill the template into a temp dir (the filled copy holds the session key; never keep it) ─────────
WORK="$(mktemp -d)"
LOG="$WORK/session.log"
trap 'kill "${LOGPID:-}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
sed "s|__PROXY_TOKEN__|$KEY|; s|__MODEL__|$MODEL|; s|__MAX_SECONDS__|$((MAX_MINUTES * 60))|" \
  "$ROOT/kaggle/gemma_server.py" > "$WORK/gemma_server.py"
cat > "$WORK/kernel-metadata.json" <<EOF
{
  "id": "$USERNAME/$SLUG",
  "title": "$SLUG",
  "code_file": "gemma_server.py",
  "language": "python",
  "kernel_type": "script",
  "is_private": "true",
  "enable_gpu": "true",
  "enable_tpu": "false",
  "enable_internet": "true",
  "machine_shape": "",
  "dataset_sources": [],
  "competition_sources": [],
  "kernel_sources": [],
  "model_sources": []
}
EOF

echo "Pushing notebook (private, GPU, capped at ${MAX_MINUTES} minutes) ..."
PUSH_OUT="$("$KAGGLE" kernels push -p "$WORK" -t "$((MAX_MINUTES * 60))" 2>&1 || true)"
echo "$PUSH_OUT" | tail -1
# The CLI can exit 0 on a rejected push (e.g. "Maximum batch GPU session count of 2 reached"); never continue
# in that case, or the log follower would attach to an older session whose key does not match.
echo "$PUSH_OUT" | grep -qi 'successfully pushed' || die "kernel push failed - Kaggle allows 2 GPU sessions at once; wait for one to end"
STARTED_AT="$(date -u +%H:%M)"

# ── follow the session log until the tunnel URL shows up ─────────────────────
follow_logs() { "$KAGGLE" kernels logs -f "$USERNAME/$SLUG" >> "$LOG" 2>&1 & LOGPID=$!; }
follow_logs
echo "Waiting for Ollama, the model download and the tunnel (usually 5-10 minutes) ..."
URL=""
for ((waited = 0; waited < WAIT_SECONDS; waited += 5)); do
  URL="$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com/v1' "$LOG" 2>/dev/null | head -1 || true)"
  [ -n "$URL" ] && break
  if grep -qE '^Traceback|SystemExit|ollama did not start' "$LOG" 2>/dev/null; then
    tail -15 "$LOG" | sed "s/$KEY/<KEY>/g" >&2
    die "the Kaggle session failed (see above)"
  fi
  # the stream can end early if the session had not started yet - reattach
  kill -0 "$LOGPID" 2>/dev/null || follow_logs
  sleep 5
done
[ -n "$URL" ] || { tail -10 "$LOG" | sed "s/$KEY/<KEY>/g" >&2; die "no tunnel URL after ${WAIT_SECONDS}s"; }
echo "Tunnel: $URL"

# ── point RepoMind at it (git-ignored .env.local; keeps unrelated lines) ─────
ENV_LOCAL="$ROOT/.env.local"
touch "$ENV_LOCAL" && chmod 600 "$ENV_LOCAL"
grep -vE '^(LOCAL_LLM_ENDPOINTS|LOCAL_LLM_API_KEY)=' "$ENV_LOCAL" > "$WORK/env.keep" || true
{
  cat "$WORK/env.keep"
  echo "LOCAL_LLM_ENDPOINTS=${URL}${FALLBACK_ENDPOINT:+,$FALLBACK_ENDPOINT}"
  echo "LOCAL_LLM_API_KEY=$KEY"
} > "$ENV_LOCAL"

echo "Restarting ai-provider-service ..."
(cd "$ROOT" && docker compose up -d ai-provider-service 2>&1 | grep -v obsolete | tail -1)
for _ in $(seq 1 40); do
  curl -s -m 3 localhost:8085/actuator/health | grep -q UP && break
  sleep 3
done

# ── end-to-end check through the backend ─────────────────────────────────────
REPLY="$(curl -s -m 120 -X POST localhost:8085/internal/ai/generate -H 'Content-Type: application/json' \
  -d '{"provider":"LOCAL","model":"x","systemPrompt":"Reply with exactly OK","userPrompt":"health-check","temperature":0.0,"maxTokens":8}' || true)"
echo "Backend reply: $REPLY"
echo "$REPLY" | grep -q '"text":"OK"' || die "the backend did not get an answer through the tunnel"

echo
echo "Done. Session started ${STARTED_AT} UTC, ends by itself after ${MAX_MINUTES} minutes."
"$KAGGLE" quota | sed -n '1,3p'
