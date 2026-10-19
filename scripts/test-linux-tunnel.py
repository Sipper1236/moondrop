#!/usr/bin/env python3
"""Offline firewall regression. A fresh user+network namespace is mandatory; never touches host routing."""
import errno,fcntl,importlib.util,ipaddress,json,os,pathlib,select,socket,struct,subprocess,sys,time,unittest
ROOT=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('tunnel_helper',ROOT/'linux-tunnel-helper.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
class ProfilePolicy(unittest.TestCase):
 def test_private_and_mapped_addresses_rejected(self):
  for ip in ('127.0.0.1','192.168.1.1','169.254.169.254','::1','::ffff:192.168.1.1','fc00::1','fe80::1','224.0.0.1','0.0.0.0'):
   with self.assertRaises(ValueError):h.public_address(ip)
 def test_no_files_routing_or_insecure_tls(self):
  base={'type':'shadowsocks','server':'93.184.216.34','server_port':8388,'method':'chacha20-ietf-poly1305','password':'test'}
  h.validate_profile(base)
  for extra in ({'bind_interface':'eth0'},{'detour':'direct'},{'password_file':'/etc/shadow'},{'method':'rc4-md5'}):
   with self.assertRaises(ValueError):h.validate_profile(dict(base,**extra))
  bad={'type':'trojan','server':'93.184.216.34','server_port':443,'password':'test','tls':{'enabled':True,'insecure':True,'server_name':'example.com'}}
  with self.assertRaises(ValueError):h.validate_profile(bad)
 def test_config_has_real_chain_and_no_direct_outbound(self):
  entry={'type':'shadowsocks','server':'93.184.216.34','server_port':8388,'method':'chacha20-ietf-poly1305','password':'entry'}
  exit=dict(entry,server='93.184.216.35',password='exit');config=h.configuration(exit,entry)
  self.assertEqual('entry',config['outbounds'][1]['detour']);self.assertTrue(config['inbounds'][0]['strict_route']);self.assertEqual('proxy',config['dns']['servers'][0]['detour'])
  self.assertFalse(any(o['type']=='direct' for o in config['outbounds']))

class ActualTunnel(unittest.TestCase):
 def test_real_engine_tun_route_and_failclosed_crash(self):
  core=os.environ.get('MOONDROP_TUN_TEST_CORE')
  if '--namespace' not in sys.argv or not core:self.skipTest('Set MOONDROP_TUN_TEST_CORE inside the isolated namespace')
  original=os.environ.get('MOONDROP_TEST_HOST_NET')
  if not original or str(pathlib.Path('/proc/self/ns/net').readlink())==original:raise RuntimeError('Refusing host network test')
  import http.server,threading,tempfile
  subprocess.run(['ip','link','set','lo','up'],check=True)
  wire=NamespaceFirewall.tun('internet0')
  subprocess.run(['ip','addr','add','93.184.216.34/24','dev','internet0'],check=True)
  subprocess.run(['ip','link','set','internet0','up'],check=True)
  subprocess.run(['ip','route','add','default','dev','internet0'],check=True)
  class Target(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    body=b'moondrop-full-device-passed';self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
   def log_message(self,*args):pass
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Target);threading.Thread(target=server.serve_forever,daemon=True).start()
  remote=client=None
  try:
   with tempfile.TemporaryDirectory(prefix='moondrop-real-tun-',dir=os.environ.get('TMPDIR')) as folder:
    folder=pathlib.Path(folder)
    remote_config={'log':{'level':'error'},'inbounds':[{'type':'shadowsocks','tag':'fixture','listen':'93.184.216.34','listen_port':18388,'method':'chacha20-ietf-poly1305','password':'namespace-fixture'}],'outbounds':[{'type':'direct','tag':'local-target'}],'route':{'rules':[{'inbound':['fixture'],'action':'route','outbound':'local-target','override_address':'127.0.0.1','override_port':server.server_port}]}}
    rp=folder/'remote.json';rp.write_text(json.dumps(remote_config));rl=(folder/'remote.log').open('wb');remote=subprocess.Popen([core,'run','-c',str(rp)],stdout=rl,stderr=rl)
    first={'type':'shadowsocks','server':'93.184.216.34','server_port':18388,'method':'chacha20-ietf-poly1305','password':'namespace-fixture'}
    config=h.configuration(first)
    # A TUN stands in for a physical NIC in this offline namespace; auto-detection correctly ignores virtual VPN devices.
    config['route']['auto_detect_interface']=False;config['route']['default_interface']='internet0';config['log']={'level':'debug'}
    cp=folder/'client.json';cp.write_text(json.dumps(config));cl=(folder/'client.log').open('wb')
    h.set_firewall(first);client=subprocess.Popen([core,'run','-c',str(cp)],stdout=cl,stderr=cl)
    deadline=time.monotonic()+12
    while time.monotonic()<deadline:
     if client.poll() is not None:self.fail((folder/'client.log').read_text())
     if subprocess.run(['ip','link','show','dev',h.TUN],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
     time.sleep(.1)
    else:self.fail('Actual TUN was not created: '+(folder/'client.log').read_text())
    # Interface creation precedes final policy-route installation; wait for engine startup.
    time.sleep(.5)
    # Unproxied application socket: only the actual TUN can deliver it to the encrypted fixture.
    try:
     with socket.create_connection(('198.18.0.42',18080),timeout=8) as connection:
      connection.sendall(b'GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n');body=b''
      while chunk:=connection.recv(65536):body+=chunk
    except OSError:
     self.fail('Client: '+(folder/'client.log').read_text()+' Remote: '+(folder/'remote.log').read_text()+' Route: '+subprocess.check_output(['ip','-j','route','get','198.18.0.42'],text=True)+' Rules: '+subprocess.check_output(['nft','list','table','inet',h.TABLE],text=True))
    self.assertIn(b'moondrop-full-device-passed',body)
    client.kill();client.wait(timeout=5);self.assertTrue(h.table_exists())
    with socket.socket() as connection:
     connection.settimeout(.4)
     self.assertNotEqual(0,connection.connect_ex(('93.184.216.53',443)))
    # Snapshot namespace-only routing left by abrupt engine death, for broker cleanup verification.
    # Broker-owned state is redirected to this test's private directory, never /run on the host.
    old_state=h.STATE;h.STATE=folder/'state';h.STATE.mkdir();(h.STATE/'routing-owned').write_text('20883 18083-18093\n')
    try:
     h.cleanup_routes()
     for family in ('-4','-6'):self.assertFalse(any(18083<=r.get('priority',0)<=18093 for r in h.owned_rules(family)))
     self.assertTrue(h.table_exists()) # Route cleanup must never release strict protection.
    finally:h.STATE=old_state
  finally:
   for process in (client,remote):
    if process is not None and process.poll() is None:process.terminate();process.wait(timeout=5)
   server.shutdown();server.server_close();h.clear_firewall();os.close(wire)
   for log in (locals().get('rl'),locals().get('cl')):
    if log:log.close()
   for family in ('-4','-6'):
    rules=json.loads(subprocess.check_output(['ip',family,'-j','rule'],text=True))
    for rule in rules:
     if 18083<=rule.get('priority',0)<18115:subprocess.run(['ip',family,'rule','del','pref',str(rule['priority'])],check=True)
    subprocess.run(['ip',family,'route','flush','table','20883'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

class NamespaceFirewall(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  if '--namespace' not in sys.argv:raise unittest.SkipTest('Run with unshare -Urn and --namespace for kernel firewall tests')
  original=os.environ.get('MOONDROP_TEST_HOST_NET')
  if not original or str(pathlib.Path('/proc/self/ns/net').readlink())==original:raise RuntimeError('Refusing to test without proof of a new network namespace')
  subprocess.run(['ip','link','set','lo','up'],check=True)
  cls.wire=cls.tun('wire0');cls.tunnel=cls.tun(h.TUN)
  for interface,ipv4,ipv6 in [('wire0','93.184.216.2/24','2001:4860:feed::2/64'),(h.TUN,'172.28.199.1/30','fd79:6d6f:6f6e::1/126')]:
   subprocess.run(['ip','addr','add',ipv4,'dev',interface],check=True);subprocess.run(['ip','-6','addr','add',ipv6,'dev',interface,'nodad'],check=True);subprocess.run(['ip','link','set',interface,'up'],check=True)
  subprocess.run(['ip','route','add','default','dev','wire0'],check=True);subprocess.run(['ip','-6','route','add','default','dev','wire0'],check=True)
 @classmethod
 def tun(cls,name):
  fd=os.open('/dev/net/tun',os.O_RDWR|os.O_NONBLOCK);fcntl.ioctl(fd,0x400454ca,struct.pack('16sH',name.encode(),0x0001|0x1000));return fd
 @classmethod
 def tearDownClass(cls):
  h.clear_firewall()
  for fd in (cls.wire,cls.tunnel):os.close(fd)
 def flush(self,fd):
  while True:
   try:os.read(fd,65536)
   except BlockingIOError:return
 def packet(self,fd,target,port,udp=True,expect=True):
  self.flush(fd);family=socket.AF_INET6 if ':' in target else socket.AF_INET
  with socket.socket(family,socket.SOCK_DGRAM if udp else socket.SOCK_STREAM) as s:
   s.setblocking(False)
   try:s.sendto(b'moondrop-regression',(target,port)) if udp else s.connect_ex((target,port))
   except OSError as e:
    if e.errno not in (errno.EPERM,errno.EACCES,errno.ENETUNREACH,errno.EHOSTUNREACH):raise
   deadline=time.monotonic()+.35;found=False
   while time.monotonic()<deadline:
    ready,_,_=select.select([fd],[],[],max(0,deadline-time.monotonic()))
    if not ready:break
    packet=os.read(fd,65536);version=packet[0]>>4
    if version==(6 if family==socket.AF_INET6 else 4):
     destination=socket.inet_ntop(family,packet[24:40] if version==6 else packet[16:20]);offset=40 if version==6 else (packet[0]&15)*4
     if destination==str(ipaddress.ip_address(target)) and len(packet)>=offset+4 and struct.unpack('!H',packet[offset+2:offset+4])[0]==port:found=True;break
   self.assertEqual(expect,found,(target,port,udp,'escaped' if found else 'blocked'))
 def test_forwarded_ipv4_ipv6_dns_tcp_udp_fail_closed(self):
  lan=self.tun('lan0')
  def checksum(data):
   if len(data)%2:data+=b'\0'
   value=sum(struct.unpack('!'+str(len(data)//2)+'H',data))
   while value>>16:value=(value&65535)+(value>>16)
   return (~value)&65535
  def forwarded(target,port,udp,expect):
   self.flush(self.wire);v6=':' in target;src=socket.inet_pton(socket.AF_INET6 if v6 else socket.AF_INET,'fd66::2' if v6 else '10.66.0.2');dst=socket.inet_pton(socket.AF_INET6 if v6 else socket.AF_INET,target);proto=17 if udp else 6
   payload=struct.pack('!HHHH',43000,port,12,0)+b'test' if udp else struct.pack('!HHIIBBHHH',43000,port,1,0,80,2,4096,0,0)
   pseudo=src+dst+(struct.pack('!I3xB',len(payload),proto) if v6 else struct.pack('!BBH',0,proto,len(payload)))
   offset=6 if udp else 16;payload=payload[:offset]+struct.pack('!H',checksum(pseudo+payload))+payload[offset+2:]
   if v6:header=struct.pack('!IHBB',6<<28,len(payload),proto,64)+src+dst
   else:
    header=struct.pack('!BBHHHBBH',69,0,20+len(payload),0,0,64,proto,0)+src+dst;header=header[:10]+struct.pack('!H',checksum(header))+header[12:]
   os.write(lan,header+payload);deadline=time.monotonic()+.3;found=False
   while time.monotonic()<deadline:
    ready,_,_=select.select([self.wire],[],[],max(0,deadline-time.monotonic()))
    if not ready:break
    packet=os.read(self.wire,65536);version=packet[0]>>4;offset=40 if version==6 else (packet[0]&15)*4
    if version==(6 if v6 else 4) and len(packet)>offset+4 and socket.inet_ntop(socket.AF_INET6 if v6 else socket.AF_INET,packet[24:40] if v6 else packet[16:20])==target and struct.unpack('!H',packet[offset+2:offset+4])[0]==port:found=True;break
   self.assertEqual(expect,found,('forwarded',target,port,udp))
  try:
   subprocess.run(['ip','addr','add','10.66.0.1/24','dev','lan0'],check=True);subprocess.run(['ip','-6','addr','add','fd66::1/64','dev','lan0','nodad'],check=True);subprocess.run(['ip','link','set','lan0','up'],check=True)
   subprocess.run(['sysctl','-w','net.ipv4.ip_forward=1','net.ipv6.conf.all.forwarding=1'],check=True,stdout=subprocess.DEVNULL)
   # Prove both IPv4 and IPv6 forwarding paths work before protection.
   for target in ('93.184.216.53','2001:4860:feed::53'):
    forwarded(target,53,True,True);forwarded(target,443,False,True)
   h.set_firewall({'type':'shadowsocks','server':'93.184.216.34','server_port':8388})
   for target in ('93.184.216.53','2001:4860:feed::53'):
    forwarded(target,53,True,False);forwarded(target,443,False,False)
   # Even forwarding to the entry itself cannot use the privileged OUTPUT exception.
   forwarded('93.184.216.34',8388,True,False)
   h.clear_firewall();forwarded('93.184.216.53',53,True,True)
  finally:h.clear_firewall();os.close(lan)
 def test_ordinary_uid_cannot_contact_the_entry_directly(self):
  first={'type':'shadowsocks','server':'93.184.216.34','server_port':8388};h.set_firewall(first)
  try:
   # The privileged networking core can reach the one intentional entry.
   self.packet(self.wire,'93.184.216.34',8388,expect=True)
   self.flush(self.wire);pid=os.fork()
   if pid==0:
    try:os.setgid(65534);os.setuid(65534)
    except OSError:os._exit(77) # A single-UID unprivileged user namespace cannot map another user.
    try:
     with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:s.sendto(b'ordinary-uid-must-not-escape',('93.184.216.34',8388))
    except PermissionError:pass
    os._exit(0)
   _,status=os.waitpid(pid,0)
   if os.waitstatus_to_exitcode(status)==77:self.skipTest('Run sudo unshare -n in CI to exercise a second UID; local user namespace maps one UID only')
   self.assertEqual(0,os.waitstatus_to_exitcode(status))
   ready,_,_=select.select([self.wire],[],[],.3);self.assertFalse(ready,'An ordinary application escaped directly to the proxy endpoint')
  finally:h.clear_firewall()
 def test_strict_ipv4_ipv6_dns_udp_and_disconnect(self):
  # Direct traffic works before protection, proving the test's baseline network path.
  self.packet(self.wire,'93.184.216.53',53,expect=True)
  first={'type':'shadowsocks','server':'93.184.216.34','server_port':8388};h.set_firewall(first)
  self.packet(self.wire,'93.184.216.34',8388,udp=False,expect=True)
  self.packet(self.wire,'93.184.216.34',8388,udp=True,expect=True)
  for target,port in [('93.184.216.53',53),('93.184.216.53',443),('2001:4860:feed::53',53),('2001:4860:feed::53',443)]:
   self.packet(self.wire,target,port,expect=False);self.packet(self.wire,target,port,udp=False,expect=False)
  # Legitimate tunnel output is allowed while the interface exists.
  subprocess.run(['ip','route','add','198.18.0.0/24','dev',h.TUN],check=True)
  self.packet(self.tunnel,'198.18.0.42',443,expect=True)
  # Engine crash/stop removes the owned TUN; firewall persists and prevents fallback.
  os.close(self.tunnel);self.__class__.tunnel=self.tun('spare0')
  for target in ('93.184.216.53','2001:4860:feed::53'):
   self.packet(self.wire,target,53,expect=False);self.packet(self.wire,target,443,udp=False,expect=False)
  self.assertTrue(h.table_exists())
  h.clear_firewall();self.packet(self.wire,'93.184.216.53',53,expect=True)
  # Atomic reconnect changes the sole allowed entry, with no broad established-flow exception.
  h.set_firewall(first);h.set_firewall(dict(first,server='93.184.216.35'))
  self.packet(self.wire,'93.184.216.34',8388,expect=False);self.packet(self.wire,'93.184.216.35',8388,expect=True)
  h.clear_firewall()
if __name__=='__main__':
 unittest.main(argv=[sys.argv[0]]+[a for a in sys.argv[1:] if a!='--namespace'])
