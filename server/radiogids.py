#!/usr/bin/env python3
"""Radiogids-proxy: cachende doorgeefluik voor de Radio France Open API.

Doel: de radio-app zonder ingebakken API-sleutel de Franse programmagids
laten ophalen. De sleutel staat alleen op deze server; telefoons melden
zich met een gedeeld token. Antwoorden worden een uur gecachet zodat het
totaal aan upstream-requests ver onder de CGU-limiet van 1000/dag blijft,
en de data wordt ongewijzigd doorgegeven (CGU art. 5.3).

Configuratie via omgevingsvariabelen (zie README.md en radiogids.service):
  RADIOFRANCE_API_KEY  persoonlijke Open API-sleutel (verplicht)
  RADIOGIDS_TOKEN      gedeeld geheim dat de app meestuurt (verplicht)
  PORT                 luisterpoort (standaard 8080)

Alleen Python-standaardbibliotheek; draait op elke kale Debian/Ubuntu.
"""
import hmac
import json
import os
import re
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_KEY = os.environ.get("RADIOFRANCE_API_KEY", "")
TOKEN = os.environ.get("RADIOGIDS_TOKEN", "")
PORT = int(os.environ.get("PORT", "8080"))

UPSTREAM = "https://openapi.radiofrance.fr/v1/graphql?x-token="
CACHE_TTL = 3600           # seconden; de gids verandert zelden binnen een uur
MAX_BODY = 8192            # bytes; een grid-query is enkele honderden bytes
MAX_UPSTREAM_PER_DAY = 800 # ruim onder de CGU-limiet van 1000 blijven

# Alleen de grid-query die de app stuurt mag erdoor; geen vrije GraphQL-toegang
# met onze sleutel, ook niet voor houders van het token.
GRID_RE = re.compile(r"^\s*\{\s*grid\(start:\s*\d+,\s*end:\s*\d+,\s*station:\s*[A-Z0-9_]+\)")

_lock = threading.Lock()
_cache = {}          # query-body -> (timestamp, antwoord-bytes)
_upstream_day = ""   # "YYYY-MM-DD" waar _upstream_count bij hoort
_upstream_count = 0


def _upstream_allowed():
    """Dagteller: True zolang we onder MAX_UPSTREAM_PER_DAY zitten."""
    global _upstream_day, _upstream_count
    today = time.strftime("%Y-%m-%d")
    if today != _upstream_day:
        _upstream_day, _upstream_count = today, 0
    if _upstream_count >= MAX_UPSTREAM_PER_DAY:
        return False
    _upstream_count += 1
    return True


class Handler(BaseHTTPRequestHandler):
    server_version = "radiogids/1"

    def _reply(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _error(self, status, message):
        self._reply(status, json.dumps({"error": message}).encode())

    def do_GET(self):
        if self.path == "/health":
            self._reply(200, b'{"ok":true}')
        else:
            self._error(404, "onbekend pad")

    def do_POST(self):
        if self.path != "/graphql":
            return self._error(404, "onbekend pad")
        auth = self.headers.get("Authorization", "")
        if not hmac.compare_digest(auth, f"Bearer {TOKEN}"):
            return self._error(401, "token ontbreekt of klopt niet")
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BODY:
            return self._error(413, "body te groot of leeg")
        body = self.rfile.read(length)
        try:
            query = json.loads(body)["query"]
        except (ValueError, KeyError, TypeError):
            return self._error(400, "geen geldige GraphQL-body")
        if not GRID_RE.match(query):
            return self._error(403, "alleen grid-queries zijn toegestaan")

        now = time.time()
        with _lock:
            for key, (ts, _) in list(_cache.items()):
                if now - ts > CACHE_TTL:
                    del _cache[key]
            hit = _cache.get(body)
            if hit:
                return self._reply(200, hit[1])
            if not _upstream_allowed():
                return self._error(429, "daglimiet richting Radio France bereikt")

        req = urllib.request.Request(
            UPSTREAM + API_KEY, data=body,
            headers={"Content-Type": "application/json"}, method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                answer = resp.read()
        except Exception as e:  # upstream plat of key geweigerd
            return self._error(502, f"Radio France antwoordt niet: {e}")

        # Alleen bruikbare antwoorden cachen; API-fouten mogen opnieuw geprobeerd
        try:
            if "errors" not in json.loads(answer):
                with _lock:
                    _cache[body] = (now, answer)
        except ValueError:
            return self._error(502, "onleesbaar antwoord van Radio France")
        self._reply(200, answer)

    def log_message(self, fmt, *args):
        # Standaardlog bevat het volledige pad; hier is dat prima (geen secrets in de URL)
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


def main():
    if not API_KEY or not TOKEN:
        sys.exit("RADIOFRANCE_API_KEY en RADIOGIDS_TOKEN moeten gezet zijn")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"radiogids-proxy luistert op poort {PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
