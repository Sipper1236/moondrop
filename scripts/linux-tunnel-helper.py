#!/usr/bin/python3
"""Privileged Linux-only Moondrop broker. Fixed engine, bounded pinned profiles, no arbitrary paths.
Strict firewall is intentionally independent of the child engine and survives this process.
Run --release explicitly to restore direct networking. Never invokes a shell.
"""
import fcntl,hashlib,ipaddress,json,os,pathlib,platform,select,signal,socket,stat,subprocess,sys,tarfile,tempfile,time,urllib.request,uuid
STATE=pathlib.Path('/run/moondrop-tunnel')
INSTALL=pathlib.Path('/usr/local/lib/moondrop')
CORE=INSTALL/'sing-box-1.14.3'
ARCHIVE_SHA='e2bdf179c15a3652955dc44867e33ca0965c9c9b91b34267dcc5a5d639a5feee'
CORE_URL='https://github.com/SagerNet/sing-box/releases/download/v1.14.3/sing-box-1.14.3-linux-amd64.tar.gz'
TUN='moondrop0'; TABLE='moondrop_tunnel'; PORT=17890
CIPHERS={'2022-blake3-aes-128-gcm','2022-blake3-aes-256-gcm','2022-blake3-chacha20-poly1305','aes-128-gcm','aes-192-gcm','aes-256-gcm','chacha20-ietf-poly1305','xchacha20-ietf-poly1305'}

def run(args,**kw):return subprocess.run(args,check=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,**kw)
def command(name):
 for directory in ('/usr/sbin','/usr/bin','/sbin','/bin'):
  path=pathlib.Path(directory)/name
  if path.is_file():return str(path)
 raise ValueError('Required system tool is missing')
def public_address(value):
 if not isinstance(value,str) or '%' in value:raise ValueError('A pinned public IP is required')
 try:a=ipaddress.ip_address(value)
 except ValueError:raise ValueError('A pinned public IP is required') from None
 if getattr(a,'ipv4_mapped',None):a=a.ipv4_mapped
 if not a.is_global or a.is_multicast:raise ValueError('Full-device mode requires public endpoints')
 return str(a)
def fields(obj,allowed,required=()):
 if not isinstance(obj,dict) or set(obj)-set(allowed) or set(required)-set(obj):raise ValueError('Unsupported profile fields')
def string(v,limit=4096):
 if not isinstance(v,str) or not 0<len(v)<=limit or '\0' in v:raise ValueError('Invalid profile value')
 return v
def validate_profile(raw):
 fields(raw,{'type','server','server_port','method','password','uuid','flow','packet_encoding','tls','transport','obfs','congestion_control','udp_relay_mode'},('type','server','server_port'))
 p=json.loads(json.dumps(raw));kind=p['type']
 if kind not in {'shadowsocks','vless','trojan','hysteria2','tuic'}:raise ValueError('Unsupported protected protocol')
 p['server']=public_address(p['server'])
 if type(p['server_port']) is not int or not 1<=p['server_port']<=65535:raise ValueError('Invalid server port')
 if kind=='shadowsocks':
  if p.get('method') not in CIPHERS:raise ValueError('Modern Shadowsocks AEAD encryption is required')
  string(p.get('password'));fields(p,{'type','server','server_port','method','password'},('method','password'))
 else:
  tls=p.get('tls');fields(tls,{'enabled','insecure','server_name','alpn','utls','reality'},('enabled','server_name'))
  if tls['enabled'] is not True or tls.get('insecure',False) is not False:raise ValueError('Verified TLS is required')
  string(tls['server_name'],253)
  if any(ord(c)<=32 for c in tls['server_name']):raise ValueError('Invalid TLS identity')
  if 'alpn' in tls:
   if not isinstance(tls['alpn'],list) or len(tls['alpn'])>16:raise ValueError('Invalid ALPN')
   for value in tls['alpn']:string(value,255)
  if 'utls' in tls:
   fields(tls['utls'],{'enabled','fingerprint'},('enabled','fingerprint'))
   if tls['utls']['enabled'] is not True or tls['utls']['fingerprint'] not in {'chrome','firefox','edge','safari','360','qq','ios','android','random','randomized'}:raise ValueError('Unsupported TLS fingerprint')
  if 'reality' in tls:
   fields(tls['reality'],{'enabled','public_key','short_id'},('enabled','public_key','short_id'))
   if kind!='vless' or tls['reality']['enabled'] is not True:raise ValueError('Invalid REALITY')
   string(tls['reality']['public_key'],128);string(tls['reality']['short_id'] or '0',16)
  if kind in {'vless','tuic'}:
   try:uuid.UUID(p.get('uuid',''))
   except (ValueError,TypeError,AttributeError):raise ValueError('Invalid proxy identity') from None
  if kind!='vless':string(p.get('password'))
  if 'flow' in p and p['flow']!='xtls-rprx-vision':raise ValueError('Unsupported flow')
  if 'packet_encoding' in p and p['packet_encoding'] not in {'xudp','packetaddr'}:raise ValueError('Unsupported packet encoding')
  if 'congestion_control' in p and p['congestion_control'] not in {'cubic','new_reno','bbr'}:raise ValueError('Unsupported congestion control')
  if 'udp_relay_mode' in p and p['udp_relay_mode'] not in {'native','quic'}:raise ValueError('Unsupported UDP relay')
  if 'transport' in p:
   t=p['transport'];fields(t,{'type','path','headers','host','service_name'},('type',))
   if kind not in {'vless','trojan'} or t['type'] not in {'ws','grpc','httpupgrade'}:raise ValueError('Unsupported transport')
   for key in ('path','host','service_name'):
    if key in t:string(t[key])
   if 'headers' in t:
    fields(t['headers'],{'Host'},('Host',));string(t['headers']['Host'])
  if 'obfs' in p:
   fields(p['obfs'],{'type','password'},('type','password'))
   if kind!='hysteria2' or p['obfs']['type'] not in {'salamander','gecko'}:raise ValueError('Unsupported obfuscation')
   string(p['obfs']['password'])
 return p

def configuration(exit,entry=None):
 exit=dict(exit,tag='proxy');outbounds=[exit]
 if entry is not None:
  if entry['type']!='shadowsocks' or exit['type'] in {'hysteria2','tuic'} or (entry['server'],entry['server_port'])==(exit['server'],exit['server_port']):raise ValueError('Two-hop requires a distinct Shadowsocks entry and TCP exit')
  exit['detour']='entry';outbounds.insert(0,dict(entry,tag='entry'))
 return {'log':{'disabled':True},'inbounds':[{'type':'tun','tag':'tun','interface_name':TUN,'address':['172.28.199.1/30','fd79:6d6f:6f6e::1/126'],'mtu':1500,'auto_route':True,'strict_route':True,'auto_redirect':False,'stack':'gvisor','iproute2_table_index':20883,'iproute2_rule_index':18083},{'type':'mixed','tag':'health','listen':'127.0.0.1','listen_port':PORT}],
 'outbounds':outbounds,'dns':{'servers':[{'type':'https','tag':'encrypted','server':'1.1.1.1','server_port':443,'path':'/dns-query','detour':'proxy'}],'final':'encrypted'},
 'route':{'auto_detect_interface':True,'rules':[{'port':53,'action':'hijack-dns'}],'final':'proxy'}}

def firewall_rules(first):
 ip=first['server'];family='ip6' if ':' in ip else 'ip';protocols=('udp',) if first['type'] in {'hysteria2','tuic'} else ('tcp','udp') if first['type']=='shadowsocks' else ('tcp',);port=first['server_port'];remote='\n'.join(f'  meta skuid 0 {family} daddr {ip} {proto} dport {port} accept' for proto in protocols)
 return f'''table inet {TABLE} {{
 chain output {{ type filter hook output priority -190; policy drop;
  oifname "lo" accept
  oifname "{TUN}" accept
{remote}
  meta nfproto ipv4 udp sport 68 udp dport 67 ip daddr 255.255.255.255 accept
  meta nfproto ipv6 udp sport 546 udp dport 547 ip6 daddr ff02::1:2 accept
  icmpv6 type {{ nd-neighbor-solicit, nd-neighbor-advert, nd-router-solicit, nd-router-advert }} ip6 daddr {{ fe80::/10, ff02::/16 }} accept
 }}
 chain forward {{ type filter hook forward priority -190; policy drop;
  oifname "{TUN}" accept
  iifname "{TUN}" accept
 }}
}}\n'''
def table_exists():return subprocess.run([command('nft'),'list','table','inet',TABLE],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
def set_firewall(first):
 rules=('delete table inet '+TABLE+'\n' if table_exists() else '')+firewall_rules(first)
 subprocess.run([command('nft'),'-f','-'],input=rules.encode(),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
def clear_firewall():
 if table_exists():run([command('nft'),'delete','table','inet',TABLE])

def root_directory(path,mode):
 path.mkdir(parents=True,exist_ok=True,mode=mode)
 info=path.lstat()
 if not stat.S_ISDIR(info.st_mode) or info.st_uid!=0 or info.st_mode&0o022:raise ValueError('Unsafe root-owned state directory')
 os.chmod(path,mode)
def status(active=False,blocked=None,message=''):
 if blocked is None:blocked=table_exists() and not active
 value={'active':bool(active),'blocked':bool(blocked),'message':message}
 temp=STATE/'status.tmp';temp.write_text(json.dumps(value)+'\n');os.chmod(temp,0o644);os.replace(temp,STATE/'status.json')
 return value
def emit(value):print(json.dumps(value),flush=True)
def ensure_core():
 if platform.machine() not in {'x86_64','amd64'}:raise ValueError('Full-device mode currently supports Linux x64')
 root_directory(INSTALL,0o755)
 digest_file=INSTALL/'engine.sha256'
 if CORE.is_file() and digest_file.is_file():
  if CORE.lstat().st_uid==0 and not CORE.lstat().st_mode&0o022 and hashlib.sha256(CORE.read_bytes()).hexdigest()==digest_file.read_text().strip():return CORE
  raise ValueError('The privileged engine failed its integrity check')
 with tempfile.TemporaryDirectory(prefix='engine-',dir=INSTALL) as tmp:
  archive=pathlib.Path(tmp)/'core.tar.gz'
  with urllib.request.urlopen(CORE_URL,timeout=120) as source,archive.open('wb') as out:
   size=0
   while block:=source.read(65536):
    size+=len(block)
    if size>100*1024*1024:raise ValueError('Engine archive exceeds size limit')
    out.write(block)
  if hashlib.sha256(archive.read_bytes()).hexdigest()!=ARCHIVE_SHA:raise ValueError('Official engine checksum did not match')
  with tarfile.open(archive,'r:gz') as tar:
   item=next((i for i in tar if i.name=='sing-box-1.14.3-linux-amd64/sing-box' and i.isfile() and not i.issym() and not i.islnk()),None)
   if item is None or item.size>200*1024*1024:raise ValueError('Invalid official engine archive')
   target=pathlib.Path(tmp)/'sing-box';target.write_bytes(tar.extractfile(item).read());os.chmod(target,0o755)
   digest=hashlib.sha256(target.read_bytes()).hexdigest();os.replace(target,CORE);digest_file.write_text(digest+'\n');os.chmod(digest_file,0o644)
 return CORE

def owned_rules(family):
 return json.loads(subprocess.check_output([command('ip'),family,'-j','rule'],stderr=subprocess.DEVNULL))
def claim_routing():
 # Refuse collisions rather than deleting another tunnel's configuration.
 for family in ('-4','-6'):
  if any(18083<=r.get('priority',0)<=18093 for r in owned_rules(family)):raise ValueError('The reserved tunnel routing rules are already in use')
  result=subprocess.run([command('ip'),family,'-j','route','show','table','20883'],capture_output=True);routes=json.loads(result.stdout or b'[]')
  if routes:raise ValueError('The reserved tunnel routing table is already in use')
 if subprocess.run([command('ip'),'link','show','dev',TUN],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:raise ValueError('The tunnel interface name is already in use')
 (STATE/'routing-owned').write_text('20883 18083-18093\n')
def cleanup_routes():
 marker=STATE/'routing-owned'
 if not marker.exists():return
 for family in ('-4','-6'):
  for rule in owned_rules(family):
   priority=rule.get('priority',0)
   if 18083<=priority<=18093:run([command('ip'),family,'rule','del','pref',str(priority)])
  subprocess.run([command('ip'),family,'route','flush','table','20883'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 marker.unlink(missing_ok=True)

def process_started(pid):
 try:return pathlib.Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1].split()[19]
 except (FileNotFoundError,IndexError,ProcessLookupError):return None
def stop_saved():
 record=STATE/'process.json'
 if record.exists():
  data=json.loads(record.read_text());pid=data['pid']
  if type(pid) is int and pid>1 and process_started(pid)==data['started']:
   try:
    exe=pathlib.Path(f'/proc/{pid}/exe').resolve()
    if exe==CORE:
     os.kill(pid,signal.SIGTERM)
     for _ in range(50):
      if process_started(pid)!=data['started']:break
      time.sleep(.1)
     else:os.kill(pid,signal.SIGKILL)
   except ProcessLookupError:pass
  record.unlink(missing_ok=True)
 (STATE/'config.json').unlink(missing_ok=True);cleanup_routes()
def stop_child(child):
 if child is not None and child.poll() is None:
  child.terminate()
  try:child.wait(timeout=5)
  except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
 (STATE/'process.json').unlink(missing_ok=True);(STATE/'config.json').unlink(missing_ok=True);cleanup_routes()

def serve():
 child=None;strict=table_exists();status(message='Direct internet is blocked until you reconnect or restore it.' if strict else 'Disconnected')
 try:
  while True:
   if child is not None and child.poll() is not None:
    stop_child(child);child=None
    if not strict:clear_firewall()
    emit(status(message='Tunnel stopped. Direct internet remains blocked.' if strict else 'Tunnel stopped.'))
   ready,_,_=select.select([sys.stdin],[],[],.4)
   if not ready:continue
   line=sys.stdin.buffer.readline(65538)
   if not line:break
   if len(line)>65536 or not line.endswith(b'\n'):raise ValueError('Tunnel request exceeds limit')
   try:
    request=json.loads(line);fields(request,{'op','strict','entry','exit'},('op',))
    if request['op']=='stop':
     stop_child(child);child=None
     if not strict:clear_firewall()
     emit(status(message='Disconnected. Direct internet remains blocked.' if strict else 'Disconnected.'));break
    if request['op']!='start' or type(request.get('strict')) is not bool:raise ValueError('Unsupported tunnel operation')
    exit=validate_profile(request.get('exit'));entry=validate_profile(request['entry']) if request.get('entry') is not None else None
    config=configuration(exit,entry);binary=ensure_core()
    stop_child(child);child=None
    # Atomic replacement keeps an existing strict firewall closed throughout reconnect.
    strict=request['strict'] or strict
    if strict:set_firewall(entry or exit)
    config_path=STATE/'config.json';fd=os.open(config_path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as out:json.dump(config,out)
    claim_routing()
    child=subprocess.Popen([str(binary),'run','-c',str(config_path)],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','HOME':'/root'})
    (STATE/'process.json').write_text(json.dumps({'pid':child.pid,'started':process_started(child.pid)}));os.chmod(STATE/'process.json',0o600)
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
     if child.poll() is not None:raise ValueError('The tunnel could not start; check protocol compatibility and system routing')
     if (pathlib.Path('/sys/class/net')/TUN).exists():
      try:
       with socket.create_connection(('127.0.0.1',PORT),timeout=.3):pass
       emit(status(active=True,blocked=False,message='Full-device tunnel active'));break
      except OSError:pass
     time.sleep(.1)
    else:raise ValueError('The tunnel did not become ready')
   except Exception as failure:
    stop_child(child);child=None
    # Only fixed validation messages leave the helper. Native/system errors may contain secrets.
    message=str(failure) if isinstance(failure,ValueError) else 'The privileged tunnel operation failed.'
    emit(dict(status(message=message),error=message))
 finally:
  stop_child(child)
  if not strict:clear_firewall()
  status(message='Direct internet remains blocked. Reconnect or explicitly restore it.' if strict else 'Disconnected')

def main():
 if os.geteuid()!=0:raise SystemExit('Administrator authorization is required')
 os.umask(0o077);root_directory(STATE,0o755)
 lock=open(STATE/'lock','a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP):signal.signal(sig,lambda *_:sys.exit(0))
 if sys.argv[1:]==['--release']:
  stop_saved();clear_firewall();emit(status(message='Direct internet restored'));return
 if sys.argv[1:]!=['--serve']:raise SystemExit('Unsupported operation')
 stop_saved();serve()
if __name__=='__main__':
 try:main()
 except Exception:print('Moondrop tunnel helper failed; existing strict protection was retained.',file=sys.stderr);sys.exit(1)
