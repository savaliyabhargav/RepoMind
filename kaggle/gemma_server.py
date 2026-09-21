"""RepoMind LLM server for a Kaggle (or Colab) GPU session.

Runs Ollama with a Gemma model, puts a bearer-token proxy in front of it, and exposes the proxy through a
Cloudflare quick tunnel (no account needed). The public URL is printed as a line starting with TUNNEL_URL=.

Placeholders (__X__) are filled in by kaggle/start.sh at push time; never commit a filled-in copy.
"""
import os
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "__PROXY_TOKEN__"
MODEL = "__MODEL__"
MAX_SECONDS = int("__MAX_SECONDS__")  # the session ends by itself, so a forgotten run cannot burn the weekly quota

OLLAMA = "http://127.0.0.1:11434"
PROXY_PORT = 8080
WORKDIR = "/kaggle/working" if os.path.isdir("/kaggle/working") else "."
STARTED = time.time()


def sh(cmd: str, check: bool = True) -> None:
    print("+", cmd, flush=True)
    subprocess.run(cmd, shell=True, check=check)


def http_ok(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=3):
            return True
    except Exception:
        return False


# ── 1. Ollama + model ────────────────────────────────────────────────────────
sh("apt-get install -y -qq zstd > /dev/null 2>&1", check=False)
sh("curl -fsSL https://ollama.com/install.sh | sh")
env = dict(os.environ, OLLAMA_HOST="127.0.0.1:11434", OLLAMA_KEEP_ALIVE="24h")
subprocess.Popen(["ollama", "serve"], env=env, stdout=open(f"{WORKDIR}/ollama.log", "w"), stderr=subprocess.STDOUT)
for _ in range(60):
    if http_ok(f"{OLLAMA}/api/version"):
        break
    time.sleep(2)
else:
    raise SystemExit("ollama did not start")
sh(f"ollama pull {MODEL}")

# Load the model into GPU memory now. Otherwise the first real request pays the load time and can exceed
# Cloudflare's ~100s limit (HTTP 524) even though nothing is wrong.
print("warming up model...", flush=True)
warm = urllib.request.Request(
    f"{OLLAMA}/api/generate", method="POST", headers={"Content-Type": "application/json"},
    data=('{"model":"%s","prompt":"hi","stream":false,"keep_alive":"24h","options":{"num_predict":1}}' % MODEL).encode())
urllib.request.urlopen(warm, timeout=900).read()
sh("nvidia-smi --query-gpu=name,memory.total --format=csv,noheader", check=False)


# ── 2. bearer-token proxy in front of Ollama ─────────────────────────────────
class Proxy(BaseHTTPRequestHandler):
    def _reply(self, status: int, body: bytes, ctype: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # client (e.g. the tunnel) gave up before we answered

    def _forward(self) -> None:
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self._reply(401, b'{"error":"unauthorized"}')
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length) if length else None
        req = urllib.request.Request(
            OLLAMA + self.path, data=data, method=self.command,
            headers={"Content-Type": self.headers.get("Content-Type", "application/json")})
        try:
            with urllib.request.urlopen(req, timeout=900) as resp:
                self._reply(resp.status, resp.read(), resp.headers.get("Content-Type", "application/json"))
        except urllib.error.HTTPError as err:
            self._reply(err.code, err.read())
        except Exception as err:  # noqa: BLE001
            self._reply(502, ('{"error":"upstream: %s"}' % err).encode())

    do_GET = do_POST = _forward

    def log_message(self, *args) -> None:  # keep the notebook log readable
        pass


server = ThreadingHTTPServer(("127.0.0.1", PROXY_PORT), Proxy)
threading.Thread(target=server.serve_forever, daemon=True).start()

# ── 3. Cloudflare quick tunnel ───────────────────────────────────────────────
sh("curl -fsSL -o /tmp/cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
   " && chmod +x /tmp/cloudflared")
tunnel = subprocess.Popen(
    ["/tmp/cloudflared", "tunnel", "--url", f"http://127.0.0.1:{PROXY_PORT}", "--no-autoupdate"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def watch_tunnel() -> None:
    printed = False
    for line in tunnel.stdout:
        match = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", line)
        if match and not printed:
            printed = True
            print(f"TUNNEL_URL={match.group(0)}/v1", flush=True)


threading.Thread(target=watch_tunnel, daemon=True).start()

# ── 4. keep the session alive until the time limit ───────────────────────────
print(f"READY model={MODEL} max_seconds={MAX_SECONDS}", flush=True)
while time.time() - STARTED < MAX_SECONDS:
    time.sleep(60)
    print(f"heartbeat {int(time.time() - STARTED)}s", flush=True)
print("time limit reached, shutting down", flush=True)
