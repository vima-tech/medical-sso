#!/usr/bin/env python3
"""演示用的「改不动的系统」：站在接入网关后面的上游。

它对统一认证一无所知，也没有引入任何接入组件，
只是把收到的请求头原样报出来——用来直观看到网关注入了什么、又拦掉了什么。

真实场景里这就是那个买来的、没源码的、或者不是 Java 写的系统。
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def _reply(self):
        body = json.dumps({
            "path": self.path,
            "method": self.command,
            "headers": {k.lower(): v for k, v in self.headers.items()},
        }, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._reply()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        self._reply()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 9099), Handler).serve_forever()
