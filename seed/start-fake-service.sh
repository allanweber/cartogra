#!/usr/bin/env bash
# Starts a minimal HTTP health endpoint on the host for local registry debugging.
# Uses the machine's LAN IP instead of localhost — the validator hard-blocks
# loopback (127/8) but allows private ranges when allow-private-endpoints=true
# (set by default in application-dev.yml).
set -euo pipefail

PORT="${1:-8090}"
PID_FILE="/tmp/cartogra-fake-service.pid"
SCRIPT_FILE="/tmp/cartogra-fake-service.py"

stop() {
  if [ -f "${PID_FILE}" ]; then
    kill "$(cat "${PID_FILE}")" 2>/dev/null || true
    rm -f "${PID_FILE}" "${SCRIPT_FILE}"
    echo "Fake service stopped."
  else
    echo "No running fake service found."
  fi
  exit 0
}

[ "${1:-}" = "stop" ] && stop

# Kill any leftover from a previous run
if [ -f "${PID_FILE}" ]; then
  kill "$(cat "${PID_FILE}")" 2>/dev/null || true
  rm -f "${PID_FILE}" "${SCRIPT_FILE}"
fi

# Write the server script to a temp file
cat > "${SCRIPT_FILE}" << 'PY'
import http.server, socketserver, json, sys

port = int(sys.argv[1])

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"status": "UP", "path": self.path}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print(f"[fake-service] {self.address_string()} {fmt % args}", flush=True)

with socketserver.TCPServer(("", port), Handler) as httpd:
    httpd.serve_forever()
PY

# Get the LAN IP — connects to an external address to discover the outbound
# interface; no packets are actually sent
HOST_IP=$(python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.connect(('8.8.8.8', 80))
print(s.getsockname()[0])
s.close()
")

# Start the server in the background
python3 "${SCRIPT_FILE}" "${PORT}" &
SERVER_PID=$!
echo "${SERVER_PID}" > "${PID_FILE}"

# Wait until the port is accepting connections
echo "Starting fake service on port ${PORT}..."
for i in $(seq 1 10); do
  if python3 -c "import socket; s=socket.socket(); s.connect(('127.0.0.1', ${PORT})); s.close()" 2>/dev/null; then
    break
  fi
  sleep 0.5
  if [ "${i}" -eq 10 ]; then
    echo "ERROR: server did not start in time."
    exit 1
  fi
done

HEALTH_URL="http://${HOST_IP}:${PORT}/health"

cat << INFO

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Fake service PID : ${SERVER_PID}
  Listening on     : 0.0.0.0:${PORT}
  LAN IP           : ${HOST_IP}
  Health URL       : ${HEALTH_URL}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Verify:
  curl ${HEALTH_URL}

Register a service via gateway:
  curl -s -X POST http://localhost:8080/api/v1/services \\
    -H 'Content-Type: application/json' \\
    -H 'Authorization: Bearer <token>' \\
    -d '{"name":"probe-test","healthEndpoint":"${HEALTH_URL}"}'

Why LAN IP and not localhost?
  The validator hard-blocks 127/8 (loopback) unconditionally.
  ${HOST_IP} is a private-range address — allowed because
  application-dev.yml sets allow-private-endpoints=true.

Stop:
  bash seed/start-fake-service.sh stop

INFO
