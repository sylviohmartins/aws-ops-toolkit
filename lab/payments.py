"""Deterministic local HTTP contract and bounded fault injection; no real payment data."""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

attempts = {}
lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        reference = self.path.rsplit('/', 1)[-1]
        with lock:
            attempt = attempts.get(reference, 0) + 1
            attempts[reference] = attempt
        status = 200
        if reference.startswith('unauthorized'):
            status = 401
        elif reference.startswith('forbidden'):
            status = 403
        elif reference.startswith('throttle') and attempt <= 2:
            status = 429
        elif reference.startswith('unavailable') and attempt <= 2:
            status = 503
        elif reference.startswith('timeout'):
            time.sleep(12)
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        if status == 429:
            self.send_header('Retry-After', '1')
        self.end_headers()
        body = {'reference': reference, 'status': 'SETTLED', 'version': 1}
        if reference.startswith('pending'):
            body['status'] = 'PENDING'
        try:
            self.wfile.write(json.dumps(body).encode())
        except BrokenPipeError:
            pass

    def log_message(self, *args):
        pass


ThreadingHTTPServer(('0.0.0.0', 8091), Handler).serve_forever()
