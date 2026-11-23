import http.server
import json
import os
from pathlib import Path
import select
import socket
import socketserver
import ssl
import subprocess
import sys
import threading
import time

work = Path(sys.argv[1]).resolve()
desk = work / "desk"
port = 18445
report = work / "desk-results.csv"
invitation = work / "invitation.txt"
process_result = work / "process-result.txt"


def wait_for_file(path, process, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            detail = process_result.read_text() if process_result.is_file() else "see desktop.log"
            raise RuntimeError(detail)
        if path.is_file() and path.stat().st_size > 0:
            return path.read_text(encoding="utf-8")
        time.sleep(.1)
    raise TimeoutError(path.name)


class Link(socketserver.ThreadingTCPServer):
    daemon_threads = True
    online = True
    dropped = 0

    def start(self):
        threading.Thread(target=self.serve_forever, daemon=True).start()


class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            if self.server.online:
                with socket.create_connection(("127.0.0.1", port), timeout=3) as receiver:
                    while self.server.online:
                        inputs, _, _ = select.select([self.request, receiver], [], [], .05)
                        if not self.server.online:
                            break
                        for connection in inputs:
                            data = connection.recv(65536)
                            if not data:
                                return
                            target = receiver if connection is self.request else self.request
                            target.sendall(data)
            self.server.dropped += 1
            time.sleep(5)
        except OSError:
            pass


def run():
    classpath = f"{work}:{desk}/jauswertung/target/classes:" + (work/"classpath.txt").read_text().strip()
    main = "de.df.jauswertung.gui.plugins.zeitnahme.IntegratedMixedPilot"
    resources = desk/"jauswertung-files/src/main/resources"
    with (work / "desktop.log").open("w") as log, Link(("127.0.0.1", 18444), Forward) as link:
        desktop = subprocess.Popen([
            str(Path(os.environ["PILOT_JAVA_HOME"])/"bin/java"), "-Xmx256m",
            "--add-exports", "java.desktop/sun.awt=ALL-UNNAMED",
            "-Dlogback.configurationFile=logback.xml", f"-Duser.home={work}",
            f"-Djava.util.prefs.PreferencesFactory={main}$IsolatedPreferences", "-cp", classpath, main,
            str(work/"synthetic.wk"), str(work/"desktop.p12"), "unused", str(port),
            str(process_result), str(invitation), str(report)
        ], cwd=resources, stdin=subprocess.PIPE, text=True, stdout=log, stderr=subprocess.STDOUT)
        try:
            def command(action):
                if desktop.poll() is not None:
                    raise RuntimeError("Desktop stopped; see desktop.log and process-result.txt")
                desktop.stdin.write(action + "\n")
                desktop.stdin.flush()

            wait_for_file(invitation, desktop, 160)
            invitation.unlink()
            command("arm")
            link.start()

            class Control(http.server.BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_GET(self):
                    try:
                        response = self.perform()
                        self.respond(200, response)
                    except Exception as error:
                        self.respond(500, {"error": str(error)})

                def perform(self):
                    if self.path == "/invite":
                        command("invite")
                        code = wait_for_file(invitation, desktop).strip()
                        invitation.unlink()
                        return {"code": code, "address": "https://127.0.0.1:18444"}
                    if self.path == "/start":
                        command("start")
                        wait_for_file(Path(str(report)+".countdown"), desktop)
                        return {}
                    if self.path == "/offline":
                        link.online = False
                        return {}
                    if self.path == "/online":
                        command("report")
                        before = wait_for_file(report, desktop)
                        report.unlink()
                        (work / "desk-offline.csv").write_text(before, encoding="utf-8")
                        if [row.split(",")[0] for row in before.splitlines()] != ["3"] or link.dropped == 0:
                            raise RuntimeError("The desk did not hold exactly the one online finish")
                        link.online = True
                        return {}
                    if self.path == "/applyWhenComplete":
                        deadline = time.monotonic() + 25
                        while time.monotonic() < deadline:
                            command("report")
                            rows = wait_for_file(report, desktop)
                            report.unlink()
                            if {row.split(",")[0] for row in rows.splitlines()} == {"3", "4", "5"}:
                                command("apply")
                                return {"rows": wait_for_file(report, desktop, 35)}
                            time.sleep(.35)
                        raise TimeoutError("The desk never received all three results after reconnect")
                    raise ValueError("Unknown test operation")

                def respond(self, status, data):
                    body = json.dumps(data).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

            with http.server.ThreadingHTTPServer(("127.0.0.1", 18443), Control) as control:
                tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                tls.load_cert_chain(work/"server.pem", work/"server.key")
                control.socket = tls.wrap_socket(control.socket, server_side=True)
                (work / "control-ready").touch()
                control.serve_forever()
        finally:
            if desktop.poll() is None:
                try:
                    command("stop")
                    desktop.wait(timeout=8)
                except (OSError, subprocess.TimeoutExpired):
                    desktop.kill()
                    desktop.wait(timeout=10)


if __name__ == "__main__":
    run()
