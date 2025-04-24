#!/usr/bin/env python3

from http.server import HTTPServer, BaseHTTPRequestHandler
from ssl import SSLContext, PROTOCOL_TLS_SERVER
import time
from pprint import pprint
import sys

nodeid = sys.argv[1]
port = int(sys.argv[2])


class PolicyServer(BaseHTTPRequestHandler):
    def do_GET(self):
        """Respond to a GET request."""
        if self.path == "/uuid":
            self.send_response(200)
            self.send_header("Content-type", "text/plain")
            self.end_headers()
            self.wfile.write(str.encode(nodeid))
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


server_address = ("", port)
httpd = HTTPServer(server_address, PolicyServer)
context = SSLContext(PROTOCOL_TLS_SERVER)
context.check_hostname = False
context.load_cert_chain(
    certfile="tests/files/keys/" + nodeid + ".cert",
    keyfile="tests/files/keys/" + nodeid + ".nopass.priv",
)
httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
httpd.serve_forever()
