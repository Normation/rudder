from http.server import HTTPServer, BaseHTTPRequestHandler
import ssl

# only tested on python3

# This server uses HTTP

PORT = 8443


class PolicyServer(BaseHTTPRequestHandler):
    def do_GET(self):
        """Respond to a GET request."""
        if self.path == "/uuid":
            self.send_response(200)
            self.send_header("Content-type", "text/plain")
            self.end_headers()
            self.wfile.write(b"root\n")
        else:
            self.send_error(404)


server_address = ('', PORT)
httpd = HTTPServer(server_address, PolicyServer)
httpd.socket = ssl.wrap_socket(httpd.socket, server_side=True,
                               certfile='tests/certs/server.cert', keyfile='tests/certs/server.nopass.priv')
httpd.serve_forever()
