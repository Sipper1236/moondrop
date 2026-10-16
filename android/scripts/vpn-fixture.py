#!/usr/bin/env python3
"""Controlled Shadowsocks remote for emulator routing tests; never changes host routing."""
import argparse, http.server, json, os, pathlib, signal, socket, subprocess, threading, time
parser=argparse.ArgumentParser()
parser.add_argument('--core',required=True)
parser.add_argument('--directory',required=True)
args=parser.parse_args()
folder=pathlib.Path(args.directory);folder.mkdir(parents=True,exist_ok=True,mode=0o700)
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body=b'moondrop-vpn-routing-passed'
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
http=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=http.serve_forever,daemon=True).start()
with socket.socket() as reserved:
    reserved.bind(('127.0.0.1',0));ss_port=reserved.getsockname()[1]
config={'log':{'level':'error'},'inbounds':[{'type':'shadowsocks','tag':'fixture','listen':'127.0.0.1','listen_port':ss_port,'method':'chacha20-ietf-poly1305','password':'moondrop-local-routing-fixture'}],'outbounds':[{'type':'direct','tag':'direct'}],'route':{'rules':[{'inbound':['fixture'],'action':'route','outbound':'direct','override_address':'127.0.0.1','override_port':http.server_port}]}}
path=folder/'remote.json';path.write_text(json.dumps(config));path.chmod(0o600)
remote=subprocess.Popen([args.core,'run','-c',str(path)],stdout=subprocess.DEVNULL,stderr=(folder/'remote.log').open('wb'))
def finish(*_):raise SystemExit()
signal.signal(signal.SIGTERM,finish);signal.signal(signal.SIGINT,finish)
try:
    for _ in range(100):
        if remote.poll() is not None:raise RuntimeError('Fixture core exited; inspect remote.log')
        try:
            with socket.create_connection(('127.0.0.1',ss_port),timeout=.1):break
        except OSError:time.sleep(.05)
    else:raise RuntimeError('Fixture listener did not open')
    (folder/'ports.json').write_text(json.dumps({'ssPort':ss_port,'httpPort':http.server_port}))
    print('VPN routing fixture ready',flush=True)
    while True:time.sleep(1)
finally:
    remote.terminate()
    try:remote.wait(timeout=5)
    except subprocess.TimeoutExpired:remote.kill();remote.wait()
    http.shutdown()
