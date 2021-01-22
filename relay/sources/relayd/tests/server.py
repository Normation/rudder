#!/usr/bin/env python3

from http.server import HTTPServer, BaseHTTPRequestHandler
import ssl
import time
from pprint import pprint

PORT = 4443

ID = "37817c4d-fbf7-4850-a985-50021f4e8f41"


class PolicyServer(BaseHTTPRequestHandler):
    def do_GET(self):
        """Respond to a GET request."""
        if self.path == "/uuid":
            self.send_response(200)
            self.send_header("Content-type", "text/plain")
            self.end_headers()
            self.wfile.write(str.encode(ID))
        elif self.path == "/stop":
            self.send_response(200)
            self.send_header("Content-type", "text/plain")
            self.end_headers()
            self.wfile.write(b"test server stopping\n")
            exit(0)
        else:
            self.send_error(404)

    def do_POST(self):
        """Respond to a POST request."""
        if self.path == "/rudder/relay-api/remote-run/nodes":
            time.sleep(0.2)
            self.send_response(200)
            self.send_header("Content-type", "text/plain")
            self.end_headers()
            self.wfile.write(b"REMOTE\n")
            f = open("target/tmp/api_test_remote.txt", "w")
            # TODO also write received parameters
            f.write("OK")
            f.close()
        else:
            self.send_error(404)


server_address = ('', PORT)
httpd = HTTPServer(server_address, PolicyServer)
httpd.socket = ssl.wrap_socket(
    httpd.socket,
    server_side=True,
    certfile='tests/files/keys/' +
    ID +
    '.cert',
    keyfile='tests/files/keys/' +
    ID +
    '.nopass.priv')
httpd.serve_forever()
