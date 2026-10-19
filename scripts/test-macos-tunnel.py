#!/usr/bin/env python3
"""Offline macOS tunnel policy/broker tests. Never modifies host networking."""
import importlib.util,json,os,pathlib,platform,subprocess,tempfile,unittest,hashlib,tarfile,urllib.request
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('mac_tunnel',pathlib.Path(__file__).with_name('macos-tunnel-helper.py'))
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
PROFILE={'type':'shadowsocks','server':'93.184.216.34','server_port':8388,'method':'chacha20-ietf-poly1305','password':'test'}
class MacTunnelPolicy(unittest.TestCase):
 def test_untrusted_addresses_and_privileged_fields_rejected(self):
  for address in ('127.0.0.1','192.168.1.1','169.254.169.254','::1','::ffff:192.168.1.1','fc00::1','fe80::1','224.0.0.1','0.0.0.0','64:ff9b::7f00:1','192.88.99.1','2002:7f00:1::1','2001::1','3fff::1'):
   with self.assertRaises(ValueError):h.validate_profile(dict(PROFILE,server=address))
  for extra in ({'bind_interface':'en0'},{'detour':'direct'},{'password_file':'/etc/master.passwd'},{'method':'rc4-md5'}):
   with self.assertRaises(ValueError):h.validate_profile(dict(PROFILE,**extra))
 def test_encrypted_dns_ipv6_and_real_chain(self):
  config=h.configuration(dict(PROFILE,server='93.184.216.35'),PROFILE)
  self.assertTrue(config['inbounds'][0]['auto_route']);self.assertEqual('gvisor',config['inbounds'][0]['stack'])
  self.assertEqual('disabled',config['inbounds'][0]['dns_mode']) # broker owns/restores DNS
  self.assertEqual(2,len(config['inbounds'][0]['address']));self.assertEqual('proxy',config['dns']['servers'][0]['detour'])
  self.assertEqual('entry',config['outbounds'][1]['detour']);self.assertNotIn('direct',[o['type'] for o in config['outbounds']])
 def test_pf_blocks_all_families_with_only_bounded_endpoint_exception(self):
  rules=h.firewall_rules(PROFILE)
  self.assertIn('block drop out quick all',rules);self.assertIn('pass quick on utun199 all',rules)
  self.assertIn('to 93.184.216.34 port 8388 user root',rules)
  self.assertNotIn('port 53',rules);self.assertNotIn('192.168.',rules)
  ipv6=h.firewall_rules(dict(PROFILE,server='2606:4700::1111'))
  self.assertIn('inet6 proto tcp',ipv6)
 def test_custom_root_pf_rules_cannot_silently_bypass_anchor(self):
  h.validate_root_rules('anchor "com.apple/*" all\n')
  for rules in ('','pass out quick all\nanchor "com.apple/*" all','anchor "moondrop" all','anchor "com.apple/*" all\npass out all'):
   with self.assertRaises(ValueError):h.validate_root_rules(rules)
 def test_sibling_quick_permit_is_rejected(self):
  def fake(args,input=None):
   if args[-1]=='Anchors':return 'com.apple/other' if args[2]=='com.apple' else ''
   return 'pass out quick all'
  with patch.object(h,'call',side_effect=fake):
   with self.assertRaises(ValueError):h.validate_sibling_anchors()
 def test_failed_pf_install_is_not_reported_as_blocked(self):
  with tempfile.TemporaryDirectory() as folder,patch.object(h,'STATE',pathlib.Path(folder)):
   (h.STATE/'firewall-owned').write_text('owned')
   with patch.object(h,'protection_active',return_value=False):value=h.write_status(None,message='Disconnected')
   self.assertFalse(value['active']);self.assertFalse(value['blocked']);self.assertIn('Direct networking may be available',value['error'])
 def test_dns_restore_never_overwrites_another_vpns_changes(self):
  with tempfile.TemporaryDirectory() as folder,patch.object(h,'STATE',pathlib.Path(folder)):
   h.atomic(h.STATE/'dns.json',{'Wi-Fi':['9.9.9.9'],'Ethernet':[]})
   calls=[]
   def fake(args,input=None):
    calls.append(args)
    if args[1]=='-getdnsservers':return '\n'.join(h.DNS) if args[-1]=='Wi-Fi' else '8.8.8.8'
    return ''
   with patch.object(h,'call',side_effect=fake):h.restore_dns()
   self.assertIn(['/usr/sbin/networksetup','-setdnsservers','Wi-Fi','9.9.9.9'],calls)
   self.assertFalse(any(c[1]=='-setdnsservers' and c[2]=='Ethernet' for c in calls))
   self.assertFalse((h.STATE/'dns.json').exists())
 def test_file_session_rejects_symlinks_and_duplicate_requests(self):
  # Session requires a non-root caller even when tests happen to run as root.
  with tempfile.TemporaryDirectory() as folder:
   path=pathlib.Path(folder);uid=os.getuid()
   if uid==0:self.skipTest('Requires ordinary user')
   path.chmod(0o700);session=h.Session(path,uid)
   try:
    (path/'request.json').write_text(json.dumps({'op':'stop','requestId':'once'}))
    self.assertEqual('once',session.read()['requestId']);self.assertIsNone(session.read())
    (path/'request.json').unlink();(path/'request.json').symlink_to('/etc/passwd')
    with self.assertRaises(OSError):session.read()
    session.write({'active':False})
    self.assertEqual(0o644,(path/'status.json').stat().st_mode&0o777)
   finally:session.close()
 def test_alive_marker_cancels_and_rejects_symlinks(self):
  with tempfile.TemporaryDirectory() as folder:
   path=pathlib.Path(folder);uid=os.getuid()
   if uid==0:self.skipTest('Requires ordinary user')
   path.chmod(0o700);session=h.Session(path,uid)
   try:
    self.assertFalse(session.live());(path/'alive').write_text('alive');self.assertTrue(session.live())
    (path/'alive').unlink();self.assertFalse(session.live())
    (path/'alive').symlink_to('/etc/passwd');self.assertFalse(session.live())
   finally:session.close()
 def test_unsafe_tls_rejected(self):
  with self.assertRaises(ValueError):h.validate_profile({'type':'trojan','server':'93.184.216.34','server_port':443,'password':'secret','tls':{'enabled':True,'insecure':True,'server_name':'example.com'}})
@unittest.skipUnless(platform.system()=='Darwin' and os.environ.get('MOONDROP_MACOS_NATIVE_TESTS')=='1','Native smoke tests require macOS and explicit opt-in')
class NativeMacSmoke(unittest.TestCase):
 def test_pf_native_syntax_without_loading_rules(self):
  # -n parses only. Never enable PF or load a live anchor in this suite.
  for profile in (PROFILE,dict(PROFILE,server='2606:4700::1111')):
   subprocess.run(['sudo','-n','/sbin/pfctl','-n','-a','com.apple/moondrop-syntax-test','-f','-'],input=h.firewall_rules(profile).encode(),capture_output=True,check=True)
 def test_official_engine_accepts_ipv6_dns_and_chain_config(self):
  arch,digest=h.ARCHIVES[platform.machine()];name='sing-box-1.14.3-darwin-'+arch
  with tempfile.TemporaryDirectory() as folder:
   folder=pathlib.Path(folder);archive=folder/'engine.tar.gz'
   with urllib.request.urlopen('https://github.com/SagerNet/sing-box/releases/download/v1.14.3/'+name+'.tar.gz',timeout=120) as response:archive.write_bytes(response.read(100*1024*1024))
   self.assertEqual(digest,hashlib.sha256(archive.read_bytes()).hexdigest())
   with tarfile.open(archive,'r:gz') as tar:
    member=tar.getmember(name+'/sing-box');self.assertTrue(member.isfile());core=folder/'sing-box';core.write_bytes(tar.extractfile(member).read());core.chmod(0o755)
   for entry in (None,PROFILE):
    config=h.configuration(dict(PROFILE,server='93.184.216.35'),entry);path=folder/'config.json';path.write_text(json.dumps(config))
    result=subprocess.run([str(core),'check','-c',str(path)],capture_output=True,text=True)
    self.assertEqual(0,result.returncode,result.stderr)
if __name__=='__main__':unittest.main()
