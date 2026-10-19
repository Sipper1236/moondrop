#!/usr/bin/env python3
"""macOS privileged, bounded-profile TUN broker. No arbitrary executable or config input.
Strict PF rules persist independently until explicit release. File IPC uses held directory FDs.
"""
import fcntl,hashlib,ipaddress,json,os,pathlib,platform,re,signal,socket,stat,subprocess,sys,tarfile,tempfile,time,urllib.request,uuid
STATE=pathlib.Path('/var/run/moondrop-tunnel').resolve()
INSTALL=pathlib.Path('/Library/PrivilegedHelperTools/Moondrop')
TUN='utun199';PORT=17890;ANCHOR='com.apple/moondrop'
DNS=['172.28.199.2','fd79:6d6f:6f6e::2']
CIPHERS={'2022-blake3-aes-128-gcm','2022-blake3-aes-256-gcm','2022-blake3-chacha20-poly1305','aes-128-gcm','aes-192-gcm','aes-256-gcm','chacha20-ietf-poly1305','xchacha20-ietf-poly1305'}
ARCHIVES={'x86_64':('amd64','6d002b74d03b478547349cc10af89e007868596e5aa931816d45da9f544715a9'),'arm64':('arm64','0360ce634a04c26a6b4fd957517987bb4f6c8950f63435ce3c5af2fcae5860c5')}
def call(args,input=None):
 return subprocess.run(args,input=input,check=True,capture_output=True,env={'PATH':'/usr/bin:/bin:/usr/sbin:/sbin','LC_ALL':'C','HOME':'/var/root'}).stdout.decode().strip()
def root_directory(path,mode=0o700):
 path.mkdir(parents=True,exist_ok=True,mode=mode)
 for component in [path,*path.parents]:
  info=component.lstat()
  if not stat.S_ISDIR(info.st_mode) or info.st_uid!=0 or info.st_mode&0o022:raise ValueError('Unsafe privileged directory')
 os.chmod(path,mode)
def atomic(path,value,mode=0o600):
 temp=path.with_name(path.name+'.tmp');fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,mode)
 with os.fdopen(fd,'w') as out:json.dump(value,out)
 os.chmod(temp,mode);os.replace(temp,path)
def public_address(value):
 if not isinstance(value,str) or '%' in value:raise ValueError('A pinned public IP is required')
 try:a=ipaddress.ip_address(value)
 except ValueError:raise ValueError('A pinned public IP is required') from None
 if getattr(a,'ipv4_mapped',None):a=a.ipv4_mapped
 if isinstance(a,ipaddress.IPv6Address):
  if a in ipaddress.IPv6Network('64:ff9b::/96'):
   public_address(str(ipaddress.IPv4Address(int(a)&0xffffffff)))
  elif a not in ipaddress.IPv6Network('2000::/3') or any(a in ipaddress.IPv6Network(n) for n in ('2001::/23','2001:db8::/32','2002::/16','3fff::/20')):raise ValueError('Full-device mode requires public endpoints')
 else:
  # Explicit exclusions give identical policy on Apple's older Python runtimes.
  ranges=('0.0.0.0/8','10.0.0.0/8','100.64.0.0/10','127.0.0.0/8','169.254.0.0/16','172.16.0.0/12','192.0.0.0/24','192.0.2.0/24','192.88.99.0/24','192.168.0.0/16','198.18.0.0/15','198.51.100.0/24','203.0.113.0/24','224.0.0.0/3')
  if any(a in ipaddress.IPv4Network(n) for n in ranges):raise ValueError('Full-device mode requires public endpoints')
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
 return {'log':{'disabled':True},'inbounds':[{'type':'tun','tag':'tun','interface_name':TUN,'address':['172.28.199.1/30','fd79:6d6f:6f6e::1/126'],'mtu':1500,'auto_route':True,'stack':'gvisor','dns_mode':'disabled'},{'type':'mixed','tag':'health','listen':'127.0.0.1','listen_port':PORT}],
 'outbounds':outbounds,'dns':{'servers':[{'type':'https','tag':'encrypted','server':'1.1.1.1','server_port':443,'path':'/dns-query','detour':'proxy'}],'final':'encrypted'},'route':{'auto_detect_interface':True,'rules':[{'port':53,'action':'hijack-dns'}],'final':'proxy'}}
def ensure_core():
 arch,digest=ARCHIVES.get(platform.machine(),(None,None))
 if not arch:raise ValueError('Unsupported macOS CPU architecture')
 root_directory(INSTALL,0o755);core=INSTALL/('sing-box-1.14.3-'+arch);record=core.with_suffix('.sha256')
 if core.exists() or record.exists():
  for path in (core,record):
   info=path.lstat()
   if not stat.S_ISREG(info.st_mode) or info.st_uid!=0 or info.st_mode&0o022:raise ValueError('Unsafe privileged engine')
  if hashlib.sha256(core.read_bytes()).hexdigest()!=record.read_text().strip():raise ValueError('Privileged engine integrity failed')
  return core
 name='sing-box-1.14.3-darwin-'+arch
 with tempfile.TemporaryDirectory(dir=INSTALL) as folder:
  archive=pathlib.Path(folder)/'archive'
  with urllib.request.urlopen('https://github.com/SagerNet/sing-box/releases/download/v1.14.3/'+name+'.tar.gz',timeout=120) as source,archive.open('wb') as out:
   size=0
   while True:
    block=source.read(65536)
    if not block:break
    size+=len(block)
    if size>100*1024*1024:raise ValueError('Engine archive too large')
    out.write(block)
  if hashlib.sha256(archive.read_bytes()).hexdigest()!=digest:raise ValueError('Official engine archive checksum failed')
  with tarfile.open(archive,'r:gz') as tar:
   member=tar.getmember(name+'/sing-box')
   if not member.isfile() or member.size>200*1024*1024:raise ValueError('Invalid engine archive')
   target=pathlib.Path(folder)/'sing-box';target.write_bytes(tar.extractfile(member).read());target.chmod(0o755)
   os.replace(target,core);record.write_text(hashlib.sha256(core.read_bytes()).hexdigest()+'\n');record.chmod(0o644)
 return core

def firewall_rules(first):
 ip=public_address(first['server']);family='inet6' if ':' in ip else 'inet';port=first['server_port']
 protocols=('udp',) if first['type'] in {'hysteria2','tuic'} else ('tcp','udp') if first['type']=='shadowsocks' else ('tcp',)
 remote='\n'.join('pass out quick '+family+' proto '+p+' from any to '+ip+' port '+str(port)+' user root keep state' for p in protocols)
 # No broad established-state exception, LAN exception or DNS exception. Root-only entry endpoint is the sole data-plane bypass.
 return 'pass quick on lo0 all\npass quick on '+TUN+' all\n'+remote+'\npass out quick inet proto udp from any port 68 to 255.255.255.255 port 67\npass out quick inet6 proto udp from any port 546 to ff02::1:2 port 547\npass out quick inet6 proto icmp6 from any to { fe80::/10, ff02::/16 } icmp6-type { routersol, routeradv, neighbrsol, neighbradv }\nblock drop out quick all\n'
def firewall_present():
 return (STATE/'firewall-owned').exists()
def validate_sibling_anchors():
 # Wildcard anchors execute in name order. A prior sibling's quick pass could
 # bypass our block, so refuse other populated filtering anchors entirely.
 pending=['com.apple'];seen=set()
 while pending:
  parent=pending.pop()
  if parent in seen or len(seen)>64 or parent.count('/')>8:raise ValueError('Unsupported PF anchor hierarchy')
  seen.add(parent)
  for value in call(['/sbin/pfctl','-a',parent,'-s','Anchors']).splitlines():
   name=value.strip()
   if not re.fullmatch(r'[A-Za-z0-9_.:/-]+',name):raise ValueError('Unsupported PF anchor name')
   name=name if name.startswith(parent+'/') else parent+'/'+name
   if name==ANCHOR:continue
   if call(['/sbin/pfctl','-a',name,'-sr']):raise ValueError('Strict mode cannot coexist with another populated PF filtering anchor')
   pending.append(name)
def protection_active():
 try:
  validate_root_rules(call(['/sbin/pfctl','-sr']));validate_sibling_anchors()
  enabled='Status: Enabled' in call(['/sbin/pfctl','-s','info'])
  return enabled and 'block drop out quick all' in call(['/sbin/pfctl','-a',ANCHOR,'-sr'])
 except Exception:return False
def validate_root_rules(rules):
 # A modified root ruleset can override a nested anchor or permit traffic before it.
 # Stock macOS contains this wildcard anchor and no root pass rules. Refuse custom layouts.
 lines=[line.strip() for line in rules.splitlines() if line.strip()]
 if lines!=['anchor "com.apple/*" all']:raise ValueError('Strict mode requires the stock macOS PF filter anchor; custom firewalls are not modified')
def set_firewall(first):
 validate_root_rules(call(['/sbin/pfctl','-sr']));validate_sibling_anchors()
 # Refuse to claim an anchor belonging to somebody else after a fresh install.
 existing=call(['/sbin/pfctl','-a',ANCHOR,'-sr'])
 if existing and not firewall_present():raise ValueError('The Moondrop PF anchor is already in use')
 rules=firewall_rules(first).encode();call(['/sbin/pfctl','-n','-a',ANCHOR,'-f','-'],rules)
 (STATE/'firewall-owned').write_text('owned\n')
 call(['/sbin/pfctl','-a',ANCHOR,'-f','-'],rules)
 if not (STATE/'pf-token').exists():
  result=subprocess.run(['/sbin/pfctl','-E'],capture_output=True,text=True,check=True)
  match=re.search(r'Token\s*:\s*(\d+)',result.stdout+'\n'+result.stderr)
  if not match:raise ValueError('Could not acquire PF enable reference; protection remains installed')
  (STATE/'pf-token').write_text(match.group(1))
 # Existing PF states skip filtering. Intentional strict-mode transition invalidates them once.
 for network in ('0.0.0.0/0','::/0'):call(['/sbin/pfctl','-k',network])
 if not protection_active():raise ValueError('PF protection could not be verified')
def clear_firewall():
 if firewall_present():
  call(['/sbin/pfctl','-a',ANCHOR,'-f','-'],b'');(STATE/'firewall-owned').unlink()
 token=STATE/'pf-token'
 if token.exists():call(['/sbin/pfctl','-X',token.read_text().strip()]);token.unlink()

def services():
 return [s for s in call(['/usr/sbin/networksetup','-listallnetworkservices']).splitlines()[1:] if s and not s.startswith('*')]
def dns_value(service):
 lines=call(['/usr/sbin/networksetup','-getdnsservers',service]).splitlines()
 if len(lines)==1 and lines[0].startswith("There aren't any DNS Servers set on "):return []
 for line in lines:ipaddress.ip_address(line)
 return lines
def set_dns():
 marker=STATE/'dns.json';saved=json.loads(marker.read_text()) if marker.exists() else {}
 for service in services():
  if service not in saved:
   saved[service]=dns_value(service);atomic(marker,saved)
  call(['/usr/sbin/networksetup','-setdnsservers',service,*DNS])
def restore_dns():
 marker=STATE/'dns.json'
 if not marker.exists():return
 saved=json.loads(marker.read_text());left={}
 for service,values in saved.items():
  try:
   # Do not overwrite network changes made by the user or another VPN.
   if dns_value(service)==DNS:call(['/usr/sbin/networksetup','-setdnsservers',service,*(values or ['Empty'])])
  except Exception:left[service]=values
 if left:atomic(marker,left);raise ValueError('Some DNS settings could not be restored; retry Restore direct internet')
 marker.unlink()

def process_identity(pid):
 try:return call(['/bin/ps','-p',str(pid),'-o','lstart='])
 except subprocess.CalledProcessError:return ''
def stop_saved():
 record=STATE/'process.json'
 if record.exists():
  data=json.loads(record.read_text());pid=data['pid']
  if type(pid) is int and pid>1 and process_identity(pid)==data['started']:
   # Match both birth time and the fixed privileged command before signalling.
   cmd=call(['/bin/ps','-p',str(pid),'-o','command='])
   if cmd==data['command'] and cmd.startswith(str(INSTALL)+'/sing-box-1.14.3-'):
    try:
     os.kill(pid,signal.SIGTERM)
     for _ in range(50):
      if process_identity(pid)!=data['started']:break
      time.sleep(.1)
     else:os.kill(pid,signal.SIGKILL)
    except ProcessLookupError:pass
  record.unlink()
 (STATE/'config.json').unlink(missing_ok=True);restore_dns()

class Session:
 def __init__(self,path,uid):
  self.uid=uid;self.path=pathlib.Path(path)
  if uid<=0 or not self.path.is_absolute():raise ValueError('Invalid session owner')
  self.fd=os.open(self.path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
  info=os.fstat(self.fd)
  if info.st_uid!=uid or info.st_mode&0o077:os.close(self.fd);raise ValueError('Session must be private and owned by requesting user')
  self.last=None
 def live(self):
  try:info=os.stat('alive',dir_fd=self.fd,follow_symlinks=False)
  except FileNotFoundError:return False
  return stat.S_ISREG(info.st_mode) and info.st_uid==self.uid and info.st_nlink==1
 def read(self):
  try:fd=os.open('request.json',os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=self.fd)
  except FileNotFoundError:return None
  with os.fdopen(fd,'rb') as stream:
   info=os.fstat(stream.fileno())
   if not stat.S_ISREG(info.st_mode) or info.st_uid!=self.uid or info.st_nlink!=1 or info.st_size>65536:raise ValueError('Unsafe tunnel request')
   content=stream.read(65537)
  if len(content)>65536:raise ValueError('Tunnel request too large')
  if content==self.last:return None
  self.last=content;value=json.loads(content);fields(value,{'op','strict','entry','exit','requestId'},('op',))
  if 'requestId' in value:string(value['requestId'],128)
  return value
 def write(self,value):
  # User owns directory: never follow, truncate or chown paths it can substitute.
  name='status-'+uuid.uuid4().hex+'.tmp'
  fd=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o644,dir_fd=self.fd)
  os.fchmod(fd,0o644)
  with os.fdopen(fd,'w') as out:json.dump(value,out)
  os.rename(name,'status.json',src_dir_fd=self.fd,dst_dir_fd=self.fd)
 def close(self):os.close(self.fd)
def write_status(session,active=False,message='',request_id=None,error=None):
 protected=firewall_present() and protection_active()
 if firewall_present() and not protected:
  active=False;message='Strict protection could not be verified. Direct networking may be available.';error=message
 value={'active':active,'blocked':protected and not active,'message':message,'brokerPid':os.getpid()}
 if request_id is not None:value['requestId']=request_id
 if error:value['error']=error
 atomic(STATE/'status.json',value,0o644)
 if session:session.write(value)
 return value

def serve(session,owner_pid,owner_started):
 child=None;strict=firewall_present();request_id=None
 def stop():
  nonlocal child
  if child is not None:
   if child.poll() is None:
    child.terminate()
    try:child.wait(timeout=5)
    except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
   child=None
  stop_saved()
 write_status(session,message='Direct internet remains blocked' if strict else 'Ready')
 try:
  while True:
   if not session.live() or process_identity(owner_pid)!=owner_started:break
   if child is not None and child.poll() is not None:
    stop();write_status(session,message='Tunnel stopped. Direct internet remains blocked.' if strict else 'Tunnel stopped.',request_id=request_id)
   try:
    request=session.read()
    if request:
     request_id=request.get('requestId');op=request['op']
     if op in {'stop','release'}:
      stop()
      if op=='release':clear_firewall();strict=False
      write_status(session,message='Direct internet remains blocked.' if strict else 'Disconnected.',request_id=request_id);break
     if op!='start' or type(request.get('strict')) is not bool:raise ValueError('Invalid tunnel operation')
     exit=validate_profile(request.get('exit'));entry=validate_profile(request['entry']) if request.get('entry') is not None else None
     config=configuration(exit,entry)
     if not session.live() or process_identity(owner_pid)!=owner_started:break
     core=ensure_core()
     if not session.live() or process_identity(owner_pid)!=owner_started:break
     stop()
     # Never silently reuse an interface owned by another application.
     if subprocess.run(['/sbin/ifconfig',TUN],capture_output=True).returncode==0:raise ValueError('Reserved tunnel interface is already in use')
     strict=request['strict'] or strict
     if not session.live() or process_identity(owner_pid)!=owner_started:break
     if strict:set_firewall(entry or exit)
     if not session.live() or process_identity(owner_pid)!=owner_started:break
     atomic(STATE/'config.json',config)
     command=[str(core),'run','-c',str(STATE/'config.json')]
     if not session.live() or process_identity(owner_pid)!=owner_started:break
     child=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env={'PATH':'/usr/bin:/bin:/usr/sbin:/sbin','HOME':'/var/root'})
     atomic(STATE/'process.json',{'pid':child.pid,'started':process_identity(child.pid),'command':' '.join(command)})
     deadline=time.monotonic()+20
     while time.monotonic()<deadline:
      if not session.live() or process_identity(owner_pid)!=owner_started:raise ValueError('Tunnel request was cancelled')
      if child.poll() is not None:raise ValueError('Tunnel engine could not start')
      if subprocess.run(['/sbin/ifconfig',TUN],capture_output=True).returncode==0:
       try:
        with socket.create_connection(('127.0.0.1',PORT),timeout=.3):pass
        break
       except OSError:pass
      time.sleep(.1)
     else:raise ValueError('Tunnel did not become ready')
     if not session.live() or process_identity(owner_pid)!=owner_started:break
     set_dns();write_status(session,True,'Full-device tunnel active',request_id)
   except Exception as failure:
    stop()
    message=str(failure) if isinstance(failure,ValueError) else 'Privileged tunnel operation failed'
    write_status(session,message=message,request_id=request_id,error=message)
   if process_identity(owner_pid)!=owner_started:break
   time.sleep(.2)
 finally:
  try:
   stop();write_status(session,message='Direct internet remains blocked. Reconnect or Restore direct internet.' if strict else 'Disconnected.',request_id=request_id)
  except Exception:
   message='Tunnel cleanup needs recovery. Use Restore direct internet to retry DNS restoration.'
   write_status(session,message=message,request_id=request_id,error=message)
  finally:session.close()
def main():
 if platform.system()!='Darwin' or os.geteuid()!=0:raise SystemExit('macOS administrator authorization required')
 os.umask(0o077);root_directory(STATE,0o755)
 with (STATE/'lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP):signal.signal(sig,lambda *_:sys.exit(0))
  if sys.argv[1:]==['--release']:
   stop_saved();clear_firewall();write_status(None,message='Direct internet restored');return
  if len(sys.argv)!=5 or sys.argv[1]!='--serve':raise ValueError('Unsupported operation')
  uid=int(sys.argv[3]);owner_pid=int(sys.argv[4])
  if owner_pid<=1 or call(['/bin/ps','-p',str(owner_pid),'-o','uid=']).strip()!=str(uid):raise ValueError('Invalid application owner')
  started=process_identity(owner_pid)
  if not started:raise ValueError('Application is not running')
  session=Session(sys.argv[2],uid)
  if not session.live():session.close();return
  try:stop_saved()
  except Exception:
   message='Saved tunnel cleanup needs recovery. Use Restore direct internet to retry.'
   write_status(session,message=message,error=message);session.close();return
  serve(session,owner_pid,started)
if __name__=='__main__':
 try:main()
 except Exception:
  print('Moondrop tunnel helper failed; existing strict protection was retained.',file=sys.stderr);sys.exit(1)
