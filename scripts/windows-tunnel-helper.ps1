# Moondrop Windows privileged broker. Run only the installed, administrator-owned copy.
# Strict WFP filters persist independently of PowerShell, the app, and sing-box.
[CmdletBinding()]
param([ValidateSet('Install','Connect','Disconnect','Release','Status','Watch','Validate')][string]$Action='Status',
      [string]$RequestPath, [string]$ExpectedHash, [string]$Session, [byte[]]$VerifiedSource)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
$CommonData=[Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
$Root=Join-Path $CommonData 'Moondrop\Tunnel'
$Installed=Join-Path $Root 'windows-tunnel-helper.ps1'
$Core=Join-Path $Root 'sing-box.exe'
$State=Join-Path $Root 'state.json'
$StatusFile=Join-Path $Root 'status.json'
$Interface='Moondrop'
$Version='1.14.3'
$Hashes=@{'AMD64'='d8b86ef58936dba9230ec6a57ffa4243933f3c14ef4809828b6c553440ed1518';'ARM64'='195b35424c6e3f5913954c7f08c5d8ba2d2199f6d18eb27a3972a5a29cdaa78c'}
function Assert-Fields($Value,$Allowed,$Required=@()) {
 if($null -eq $Value -or $Value -isnot [System.Collections.IDictionary]){throw 'Expected JSON object'}
 foreach($k in $Value.Keys){if($k -notin $Allowed){throw 'Unsupported profile field'}}
 foreach($k in $Required){if(-not $Value.Contains($k)){throw 'Required profile field missing'}}
}
function Convert-Map($Value){
 if($null -eq $Value){return $null}
 if($Value -is [System.Management.Automation.PSCustomObject]){$r=@{};foreach($p in $Value.PSObject.Properties){$r[$p.Name]=Convert-Map $p.Value};return $r}
 if($Value -is [System.Collections.IList]){return ,@($Value | ForEach-Object {Convert-Map $_})}
 return $Value
}
function Assert-String($s,$Limit=4096){if($s -isnot [string] -or $s.Length -lt 1 -or $s.Length -gt $Limit -or $s.Contains([char]0)){throw 'Invalid profile value'}}
function Public-IP($Value){
 Assert-String $Value 64
 if($Value.Contains('%')){throw 'Public literal endpoint required'}
 $ip=$null;if(-not [Net.IPAddress]::TryParse($Value,[ref]$ip)){throw 'Public literal endpoint required'}
 if($ip.IsIPv4MappedToIPv6){$ip=$ip.MapToIPv4()}
 $b=$ip.GetAddressBytes()
 if($b.Length -eq 4){
  if($b[0] -in @(0,10,127) -or $b[0] -ge 224 -or ($b[0] -eq 100 -and $b[1] -ge 64 -and $b[1] -le 127) -or ($b[0] -eq 169 -and $b[1] -eq 254) -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or ($b[0] -eq 192 -and ($b[1] -eq 168 -or $b[1] -eq 0 -or ($b[1] -eq 2))) -or ($b[0] -eq 198 -and $b[1] -in @(18,19,51)) -or ($b[0] -eq 203 -and $b[1] -eq 0 -and $b[2] -eq 113)){throw 'Non-public endpoint rejected'}
 }else{
  # Current globally routable unicast allocation only; exclude documentation,
  # Teredo/6to4 translation, ORCHID and benchmarking/special-purpose allocations.
  if(($b[0] -band 0xe0) -ne 0x20 -or ($b[0] -eq 0x20 -and $b[1] -eq 0x02) -or ($b[0] -eq 0x20 -and $b[1] -eq 0x01 -and ($b[2] -lt 2 -or ($b[2] -eq 0x0d -and $b[3] -eq 0xb8) -or ($b[2] -eq 0x20))) -or ($b[0] -eq 0x3f -and $b[1] -eq 0xff)){throw 'Non-public endpoint rejected'}
 }
 return $ip.ToString()
}
function Validate-Profile($p){
 Assert-Fields $p @('type','server','server_port','method','password','uuid','flow','packet_encoding','tls','transport','obfs','congestion_control','udp_relay_mode') @('type','server','server_port')
 if($p.type -notin @('shadowsocks','vless','trojan','hysteria2','tuic')){throw 'Unsupported protected protocol'}
 $p.server=Public-IP $p.server
 if($p.server_port -isnot [long] -and $p.server_port -isnot [int]){throw 'Invalid endpoint port'}
 if($p.server_port -lt 1 -or $p.server_port -gt 65535){throw 'Invalid endpoint port'}
 if($p.type -eq 'shadowsocks'){
  Assert-Fields $p @('type','server','server_port','method','password') @('method','password')
  if($p.method -notin @('2022-blake3-aes-128-gcm','2022-blake3-aes-256-gcm','2022-blake3-chacha20-poly1305','aes-128-gcm','aes-192-gcm','aes-256-gcm','chacha20-ietf-poly1305','xchacha20-ietf-poly1305')){throw 'Modern AEAD encryption required'}
  Assert-String $p.password
 }else{
  if(-not $p.Contains('tls')){throw 'Verified TLS required'}
  $t=$p.tls;Assert-Fields $t @('enabled','insecure','server_name','alpn','utls','reality') @('enabled','server_name')
  if($t.enabled -isnot [bool] -or -not $t.enabled -or ($t.Contains('insecure') -and $t.insecure -ne $false)){throw 'Verified TLS required'}
  Assert-String $t.server_name 253;if($t.server_name -match '\s'){throw 'Invalid TLS identity'}
  if($t.Contains('alpn')){if($t.alpn -isnot [array] -or $t.alpn.Count -gt 16){throw 'Invalid ALPN'};foreach($a in $t.alpn){Assert-String $a 255}}
  if($t.Contains('utls')){Assert-Fields $t.utls @('enabled','fingerprint') @('enabled','fingerprint');if($t.utls.enabled -ne $true -or $t.utls.fingerprint -notin @('chrome','firefox','edge','safari','360','qq','ios','android','random','randomized')){throw 'Invalid TLS fingerprint'}}
  if($t.Contains('reality')){Assert-Fields $t.reality @('enabled','public_key','short_id') @('enabled','public_key','short_id');if($p.type -ne 'vless' -or $t.reality.enabled -ne $true){throw 'Invalid REALITY'};Assert-String $t.reality.public_key 128;if($t.reality.short_id -isnot [string] -or $t.reality.short_id -notmatch '^[0-9a-fA-F]{0,16}$'){throw 'Invalid REALITY short ID'}}
  if($p.type -in @('vless','tuic')){$id=[guid]::Empty;if(-not $p.Contains('uuid') -or -not [guid]::TryParse($p.uuid,[ref]$id)){throw 'Invalid proxy identity'}}
  if($p.type -ne 'vless'){if(-not $p.Contains('password')){throw 'Missing password'};Assert-String $p.password}
  if($p.Contains('flow') -and $p.flow -ne 'xtls-rprx-vision'){throw 'Unsupported flow'}
  if($p.Contains('packet_encoding') -and $p.packet_encoding -notin @('xudp','packetaddr')){throw 'Unsupported packet encoding'}
  if($p.Contains('congestion_control') -and $p.congestion_control -notin @('cubic','new_reno','bbr')){throw 'Unsupported congestion control'}
  if($p.Contains('udp_relay_mode') -and $p.udp_relay_mode -notin @('native','quic')){throw 'Unsupported UDP relay'}
  if($p.Contains('transport')){$x=$p.transport;Assert-Fields $x @('type','path','headers','host','service_name') @('type');if($p.type -notin @('vless','trojan') -or $x.type -notin @('ws','grpc','httpupgrade')){throw 'Unsupported transport'};foreach($k in @('path','host','service_name')){if($x.Contains($k)){Assert-String $x[$k]}};if($x.Contains('headers')){Assert-Fields $x.headers @('Host') @('Host');Assert-String $x.headers.Host}}
  if($p.Contains('obfs')){Assert-Fields $p.obfs @('type','password') @('type','password');if($p.type -ne 'hysteria2' -or $p.obfs.type -notin @('salamander','gecko')){throw 'Unsupported obfuscation'};Assert-String $p.obfs.password}
 }
 return $p
}
function Read-Request {
 $f=Get-Item -LiteralPath $RequestPath -Force
 if($f.PSIsContainer -or ($f.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $f.Length -gt 65536){throw 'Unsafe request file'}
 $r=Convert-Map (Get-Content -LiteralPath $f.FullName -Raw | ConvertFrom-Json)
 Assert-Fields $r @('op','strict','exit','entry','requestId','parentPid','parentStarted','sessionPath') @('exit','strict','requestId')
 if($r.strict -isnot [bool] -or $r.requestId -isnot [string] -or $r.requestId -notmatch '^[a-zA-Z0-9-]{1,80}$'){throw 'Invalid request'}
 $r.exit=Validate-Profile $r.exit
 if($r.Contains('entry') -and $null -ne $r.entry){$r.entry=Validate-Profile $r.entry;if($r.entry.type -ne 'shadowsocks' -or $r.exit.type -in @('tuic','hysteria2') -or ($r.entry.server -eq $r.exit.server -and $r.entry.server_port -eq $r.exit.server_port)){throw 'Two-hop requires distinct Shadowsocks entry and TCP exit'}}
 return $r
}
function Configuration($r){
 $exit=$r.exit.Clone();$exit.tag='proxy';$out=@($exit)
 if($r.Contains('entry') -and $null -ne $r.entry){$exit.detour='entry';$entry=$r.entry.Clone();$entry.tag='entry';$out=@($entry,$exit)}
 return @{log=@{disabled=$true};inbounds=@(@{type='tun';tag='tun';interface_name=$Interface;address=@('172.28.199.1/30','fd79:6d6f:6f6e::1/126');mtu=1500;auto_route=$true;strict_route=$true;stack='gvisor'},@{type='mixed';tag='health';listen='127.0.0.1';listen_port=17890});outbounds=$out;dns=@{servers=@(@{type='https';tag='encrypted';server='1.1.1.1';server_port=443;path='/dns-query';detour='proxy'});final='encrypted'};route=@{auto_detect_interface=$true;rules=@(@{port=53;action='hijack-dns'});final='proxy'}}
}
function Assert-Admin {if(-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Administrator approval required'}}
function Assert-Protected($Path){
 $item=Get-Item -LiteralPath $Path -Force
 if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse point rejected'}
 $acl=Get-Acl -LiteralPath $Path
 $owner=$acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
 if($owner -notin @('S-1-5-18','S-1-5-32-544')){throw 'Administrator ownership required'}
 foreach($a in $acl.Access){if($a.AccessControlType -eq 'Allow' -and -not ($a.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and ($a.FileSystemRights -band 0xD0116) -ne 0 -and $a.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @('S-1-5-18','S-1-5-32-544','S-1-3-0')){throw 'Writable privileged path rejected'}}
}
function Assert-Ancestors {
 # Derive the location from the OS known folder, never the caller's environment.
 # Ancestors may allow creating NEW children, but may not let users replace an
 # existing protected directory (DELETE_CHILD/DELETE/WRITE_DAC/WRITE_OWNER).
 $path=$CommonData
 while($path){
  $item=Get-Item -LiteralPath $path -Force
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse ancestor rejected'}
  $acl=Get-Acl -LiteralPath $path
  foreach($a in $acl.Access){if($a.AccessControlType -eq 'Allow' -and -not ($a.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and ($a.FileSystemRights -band 0xD0040) -ne 0){$sid=$a.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value;if($sid -notin @('S-1-5-18','S-1-5-32-544','S-1-3-0','S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')){throw 'Unsafe privileged directory ancestor'}}}
  $next=Split-Path $path -Parent;if($next -eq $path){break};$path=$next
 }
}
function Initialize-Root {
 Assert-Ancestors
 $parent=Split-Path $Root
 # ProgramData's inherited Creator Owner ACL needs normalization only on a NEW tree.
 foreach($dir in @($parent,$Root)){
  if(-not (Test-Path -LiteralPath $dir)){$new=$true}else{$new=$false}
  if($new){$acl=New-Object Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false);$acl.SetOwner([Security.Principal.SecurityIdentifier]'S-1-5-32-544');foreach($sid in @('S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]$sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))};$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]'S-1-5-32-545','ReadAndExecute','None','None','Allow')));$created=New-Object IO.DirectoryInfo($dir);$created.Create($acl)}
  Assert-Protected $dir
 }
}
function Save-Private($Path,[byte[]]$Bytes,[switch]$PublicRead){
 if(Test-Path -LiteralPath $Path){Assert-Protected $Path}
 $acl=New-Object Security.AccessControl.FileSecurity
 $acl.SetAccessRuleProtection($true,$false);$acl.SetOwner([Security.Principal.SecurityIdentifier]'S-1-5-32-544')
 foreach($sid in @('S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]$sid,'FullControl','Allow')))}
 if($PublicRead){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]'S-1-5-32-545','ReadAndExecute','Allow')))}
 # .NET Framework's security-descriptor overload applies owner+DACL at creation,
 # rather than exposing a file temporarily owned by the unelevated user SID.
 $stream=[IO.File]::Create($Path,4096,[IO.FileOptions]::None,$acl)
 try{if($Bytes.Length -gt 0){$stream.Write($Bytes,0,$Bytes.Length)}}finally{$stream.Dispose()}
 Assert-Protected $Path
}
function Save-Text($Path,[string]$Text,[switch]$PublicRead){Save-Private $Path ([Text.Encoding]::UTF8.GetBytes($Text)) -PublicRead:$PublicRead}
function Write-Status($Active,$Blocked,$Message,$Id=''){
 $v=@{active=[bool]$Active;blocked=[bool]$Blocked;message=$Message;requestId=$Id}
 if($Active){$s=Read-State;if($null -ne $s){$v.pid=$s.pid;$v.started=([DateTime]::new([long]$s.started,[DateTimeKind]::Utc)).ToString('o');$v.strict=$s.strict}}
 $temp=Join-Path $Root 'status.tmp';Save-Text $temp ($v|ConvertTo-Json -Compress) -PublicRead;Move-Item -LiteralPath $temp -Destination $StatusFile -Force
 $v|ConvertTo-Json -Compress
}
function Ensure-Core {
 if(Test-Path -LiteralPath $Core){Assert-Protected $Core;$expected=(Get-Content -LiteralPath (Join-Path $Root 'engine.sha256') -Raw).Trim();if((Get-FileHash -LiteralPath $Core -Algorithm SHA256).Hash -ne $expected){throw 'Privileged engine integrity check failed'};return}
 $arch=$env:PROCESSOR_ARCHITECTURE;if(-not $Hashes.ContainsKey($arch)){throw 'Only Windows x64 and ARM64 are supported'}
 $cpu=if($arch -eq 'ARM64'){'arm64'}else{'amd64'};$name="sing-box-$Version-windows-$cpu";$zip=Join-Path $Root 'engine.zip';$newCore=Join-Path $Root 'sing-box.new'
 try{
  [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
  Save-Private $zip ([byte[]]@())
  Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/SagerNet/sing-box/releases/download/v$Version/$name.zip" -OutFile $zip -TimeoutSec 180
  if((Get-Item $zip).Length -gt 100MB -or (Get-FileHash $zip -Algorithm SHA256).Hash -ne $Hashes[$arch]){throw 'Official engine checksum mismatch'}
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive=[IO.Compression.ZipFile]::OpenRead($zip)
  try{$e=$archive.GetEntry("$name/sing-box.exe");if($null -eq $e -or $e.Length -gt 200MB){throw 'Invalid official engine archive'};Save-Private $newCore ([byte[]]@());[IO.Compression.ZipFileExtensions]::ExtractToFile($e,$newCore,$true)}finally{$archive.Dispose()}
  Save-Text (Join-Path $Root 'engine.sha256') (Get-FileHash $newCore -Algorithm SHA256).Hash
  Move-Item -LiteralPath $newCore -Destination $Core -Force
 }finally{Remove-Item -LiteralPath $zip,$newCore -Force -ErrorAction SilentlyContinue}
}
$WfpSource=@'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Net;
using System.Runtime.InteropServices;
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
public static class MoondropWfp {
 // The structs deliberately target the native 64-bit Windows ABI.
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Display { [MarshalAs(UnmanagedType.LPWStr)] public string name; [MarshalAs(UnmanagedType.LPWStr)] public string description; }
 [StructLayout(LayoutKind.Sequential)] struct Blob {public uint size;public IntPtr data;}
 [StructLayout(LayoutKind.Explicit,Size=8)] struct Union {[FieldOffset(0)]public ulong scalar;[FieldOffset(0)]public IntPtr pointer;}
 [StructLayout(LayoutKind.Sequential)] struct Value {public uint type;public Union value;}
 [StructLayout(LayoutKind.Sequential)] struct Condition {public Guid key;public uint match;public Value value;}
 [StructLayout(LayoutKind.Sequential)] struct Action {public uint type;public Guid key;}
 [StructLayout(LayoutKind.Explicit,Size=16)] struct Context {[FieldOffset(0)]public ulong raw;[FieldOffset(0)]public Guid key;}
 [StructLayout(LayoutKind.Sequential)] struct Filter {public Guid key;public Display display;public uint flags;public IntPtr provider;public Blob data;public Guid layer;public Guid sublayer;public Value weight;public uint count;public IntPtr conditions;public Action action;public Context context;public IntPtr reserved;public ulong id;public Value effectiveWeight;}
 [StructLayout(LayoutKind.Sequential)] struct Sublayer {public Guid key;public Display display;public uint flags;public IntPtr provider;public Blob data;public ushort weight;}
 [DllImport("fwpuclnt.dll",CharSet=CharSet.Unicode)] static extern uint FwpmEngineOpen0(string server,uint auth,IntPtr identity,IntPtr session,out IntPtr engine);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmEngineClose0(IntPtr engine);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmTransactionBegin0(IntPtr engine,uint flags);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmTransactionCommit0(IntPtr engine);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmTransactionAbort0(IntPtr engine);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmSubLayerAdd0(IntPtr engine,ref Sublayer layer,IntPtr security);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmSubLayerDeleteByKey0(IntPtr engine,ref Guid key);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmFilterAdd0(IntPtr engine,ref Filter filter,IntPtr security,out ulong id);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmFilterDeleteByKey0(IntPtr engine,ref Guid key);
 [DllImport("fwpuclnt.dll")] static extern uint FwpmFilterGetByKey0(IntPtr engine,ref Guid key,out IntPtr filter);
 [DllImport("fwpuclnt.dll",CharSet=CharSet.Unicode)] static extern uint FwpmGetAppIdFromFileName0(string path,out IntPtr app);
 [DllImport("fwpuclnt.dll")] static extern void FwpmFreeMemory0(ref IntPtr memory);
 [DllImport("iphlpapi.dll")] static extern uint ConvertInterfaceIndexToLuid(uint index,out ulong luid);
 static readonly Guid Sub=new Guid("f16e7c41-55aa-4f64-9239-cedc9ddc25c0");
 static readonly Guid V4=new Guid("c38d57d1-05a7-4c33-904f-7fbceee60e82"),V6=new Guid("4a72393b-319f-44bc-84c3-ba54dcb3b6b4");
 static readonly Guid R4=new Guid("e1cd9fe7-f4b5-4273-96c0-592e487b8650"),R6=new Guid("a3b42c97-9f04-4672-b87e-cee9c483257f");
 static readonly Guid Remote=new Guid("b235ae9a-1d64-49b8-a44c-5ff3d9095045"),Port=new Guid("c35a604d-d22b-4e1a-91b4-68f674ee674b"),LocalPort=new Guid("0c1ba1af-5765-453f-af22-a8f791ac775b"),App=new Guid("d78e1e87-8644-4ea5-9437-d809ecefc971"),LocalInterface=new Guid("4cd62a49-59c3-4969-b7f3-bda5d32890a4"),Flags=new Guid("632ce23b-5167-435c-86d7-e903684aa80c"),Protocol=new Guid("3971ef2b-623e-4f9a-8cb1-6e79b806b9a7");
 static Guid Key(int index){return new Guid("38916812-a19f-4d4c-b54e-"+index.ToString("x12"));}
 static void Check(uint result){if(result!=0)throw new Win32Exception(unchecked((int)result),"Windows tunnel filtering operation failed ("+result.ToString("x")+")");}
 static IntPtr Open(){IntPtr engine;Check(FwpmEngineOpen0(null,10,IntPtr.Zero,IntPtr.Zero,out engine));return engine;}
 static void Delete(IntPtr e){for(int i=0;i<64;i++){Guid k=Key(i);uint r=FwpmFilterDeleteByKey0(e,ref k);if(r!=0&&r!=0x80320003)Check(r);}}
 static Value Scalar(uint type,ulong number){return new Value{type=type,value=new Union{scalar=number}};}
 static Condition C(Guid key,uint type,ulong number){return new Condition{key=key,value=Scalar(type,number)};}
 static IntPtr Allocate(byte[] bytes,List<IntPtr> mem){IntPtr p=Marshal.AllocHGlobal(bytes.Length);Marshal.Copy(bytes,0,p,bytes.Length);mem.Add(p);return p;}
 static Condition Address(string address,List<IntPtr> mem){IPAddress ip=IPAddress.Parse(address);byte[] b=ip.GetAddressBytes();if(b.Length==4)return C(Remote,3,((uint)b[0]<<24)|((uint)b[1]<<16)|((uint)b[2]<<8)|b[3]);return new Condition{key=Remote,value=new Value{type=11,value=new Union{pointer=Allocate(b,mem)}}};}
 static void Add(IntPtr e,int key,Guid layer,bool permit,byte weight,params Condition[] conditions){
  AddOne(e,key,layer,permit,weight,conditions);
  // Block unsolicited direct inbound flows too, including replies from sockets
  // accepted on a physical NIC. Existing outbound replies retain ALE flow state.
  if(layer==V4||layer==V6)AddOne(e,key+16,layer==V4?R4:R6,permit,weight,conditions);
 }
 static void AddOne(IntPtr e,int key,Guid layer,bool permit,byte weight,params Condition[] conditions){
  int size=Marshal.SizeOf(typeof(Condition));IntPtr p=IntPtr.Zero;
  try{if(conditions.Length>0){p=Marshal.AllocHGlobal(size*conditions.Length);for(int i=0;i<conditions.Length;i++)Marshal.StructureToPtr(conditions[i],IntPtr.Add(p,size*i),false);}
   Filter f=new Filter{key=Key(key),display=new Display{name="Moondrop strict tunnel",description="Owned fail-closed filter; use Moondrop Release to restore direct traffic"},flags=1,layer=layer,sublayer=Sub,weight=Scalar(1,weight),count=(uint)conditions.Length,conditions=p,action=new Action{type=permit?0x1002u:0x1001u}};
   ulong id;Check(FwpmFilterAdd0(e,ref f,IntPtr.Zero,out id));
  }finally{if(p!=IntPtr.Zero)Marshal.FreeHGlobal(p);}
 }
 public static bool Blocked(){IntPtr e=Open();try{Guid k=Key(0);IntPtr f;uint r=FwpmFilterGetByKey0(e,ref k,out f);if(r==0){FwpmFreeMemory0(ref f);return true;}if(r==0x80320003)return false;Check(r);return false;}finally{FwpmEngineClose0(e);}}
 public static void Clear(){IntPtr e=Open();try{Check(FwpmTransactionBegin0(e,0));try{Delete(e);Guid k=Sub;uint r=FwpmSubLayerDeleteByKey0(e,ref k);if(r!=0&&r!=0x80320007)Check(r);Check(FwpmTransactionCommit0(e));}catch{FwpmTransactionAbort0(e);throw;}}finally{FwpmEngineClose0(e);}}
 public static void Apply(string core,string address,ushort port,uint tunnelIndex){
  if(IntPtr.Size!=8)throw new PlatformNotSupportedException("Use 64-bit Windows PowerShell");
  IntPtr e=Open(),app=IntPtr.Zero;var mem=new List<IntPtr>();
  try{Check(FwpmGetAppIdFromFileName0(core,out app));Check(FwpmTransactionBegin0(e,0));try{
   var s=new Sublayer{key=Sub,display=new Display{name="Moondrop strict tunnel"},flags=1,weight=ushort.MaxValue};uint r=FwpmSubLayerAdd0(e,ref s,IntPtr.Zero);if(r!=0&&r!=0x80320009)Check(r);
   Delete(e);
   // Same-sublayer high-weight permits carve exceptions from the low-weight hard
   // block. Soft permits still respect other providers' firewall block policies.
   Add(e,0,V4,false,0);Add(e,1,V6,false,0);
   var loop=C(Flags,3,1);loop.match=6;Add(e,2,V4,true,15,loop);Add(e,3,V6,true,15,loop);
   var endpoint=Address(address,mem);var appCondition=new Condition{key=App,value=new Value{type=12,value=new Union{pointer=app}}};
   Add(e,4,IPAddress.Parse(address).AddressFamily==System.Net.Sockets.AddressFamily.InterNetwork?V4:V6,true,15,endpoint,C(Port,2,port),appCondition);
   if(tunnelIndex!=0){ulong luid;Check(ConvertInterfaceIndexToLuid(tunnelIndex,out luid));IntPtr lp=Allocate(BitConverter.GetBytes(luid),mem);var c=new Condition{key=LocalInterface,value=new Value{type=4,value=new Union{pointer=lp}}};Add(e,5,V4,true,15,c);Add(e,6,V6,true,15,c);}
   // DHCP exceptions constrain BOTH server and client ports; remote port alone
   // would let unrelated UDP applications inherit an inbound bypass flow.
   // Essential local network control only; no general LAN/DNS bypass.
   Add(e,7,V4,true,14,Address("255.255.255.255",mem),C(Protocol,1,17),C(Port,2,67),C(LocalPort,2,68));
   Add(e,8,V6,true,14,Address("ff02::1:2",mem),C(Protocol,1,17),C(Port,2,547),C(LocalPort,2,546));
   // IPv6 neighbour discovery/router solicitation uses link-local multicast.
   // A masked ff02::/16 + fe80::/10 rule is restricted to ICMPv6 (protocol 58).
   int controlKey=32;foreach(var prefix in new[]{new byte[]{0xff,2},new byte[]{0xfe,0x80}}){byte[] mask=new byte[17];Array.Copy(prefix,mask,2);mask[16]=(byte)(prefix[0]==0xff?16:10);var c=new Condition{key=Remote,value=new Value{type=257,value=new Union{pointer=Allocate(mask,mem)}}};foreach(ushort type in new ushort[]{133,134,135,136}){AddOne(e,controlKey,V6,true,14,c,C(Protocol,1,58),C(LocalPort,2,type));AddOne(e,controlKey+16,R6,true,14,c,C(Protocol,1,58),C(LocalPort,2,type));controlKey++;}}
   AddOne(e,27,R4,true,14,C(Protocol,1,17),C(Port,2,67),C(LocalPort,2,68));
   AddOne(e,28,R6,true,14,C(Protocol,1,17),C(Port,2,547),C(LocalPort,2,546));
   Check(FwpmTransactionCommit0(e));
  }catch{FwpmTransactionAbort0(e);throw;}}
  finally{foreach(IntPtr p in mem)Marshal.FreeHGlobal(p);if(app!=IntPtr.Zero)FwpmFreeMemory0(ref app);FwpmEngineClose0(e);}
 }
 public static int[] Sizes(){return new[]{Marshal.SizeOf(typeof(Value)),Marshal.SizeOf(typeof(Condition)),Marshal.SizeOf(typeof(Filter)),Marshal.SizeOf(typeof(Sublayer))};}
}
'@
function Load-Wfp {if(-not ('MoondropWfp' -as [type])){Add-Type -TypeDefinition $WfpSource}}
function Read-State {if(Test-Path -LiteralPath $State){return Convert-Map (Get-Content -LiteralPath $State -Raw|ConvertFrom-Json)};return $null}
function Owned-Process($s){
 if($null -eq $s){return $null}
 $p=Get-Process -Id $s.pid -ErrorAction SilentlyContinue
 if($null -ne $p -and $p.Path -eq $Core -and $p.StartTime.ToUniversalTime().Ticks.ToString() -eq $s.started){return $p}
 return $null
}
function Stop-Core {
 $s=Read-State;$p=Owned-Process $s
 if($null -ne $p){Stop-Process -Id $p.Id -Force;$p.WaitForExit(10000)|Out-Null}
 # sing-box removes its own Wintun routes on graceful exit; force termination also
 # tears down the adapter handle. Delete only our interface's owned /1 routes.
 $a=Get-NetAdapter -Name $Interface -ErrorAction SilentlyContinue
 if($null -ne $s -and $null -ne $a){Get-NetRoute -InterfaceIndex $a.ifIndex -ErrorAction SilentlyContinue | Where-Object {$_.DestinationPrefix -in @('0.0.0.0/1','128.0.0.0/1','::/1','8000::/1')} | Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue}
 Remove-Item -LiteralPath $State -Force -ErrorAction SilentlyContinue
 Remove-Item -LiteralPath (Join-Path $Root 'config.json') -Force -ErrorAction SilentlyContinue
}
function Parent-Alive($r){
 if($r.Contains('sessionPath')){if($r.sessionPath -isnot [string] -or $r.sessionPath.Length -gt 1024 -or -not [IO.File]::Exists($r.sessionPath)){return $false}}
 if(-not $r.Contains('parentPid')){return $true}
 if($r.parentPid -isnot [int] -and $r.parentPid -isnot [long]){return $false}
 $p=Get-Process -Id $r.parentPid -ErrorAction SilentlyContinue
 if($null -eq $p){return $false}
 if($r.Contains('parentStarted')){try{if([DateTimeOffset]::Parse($r.parentStarted).ToUnixTimeMilliseconds() -ne ([DateTimeOffset]$p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()){return $false}}catch{return $false}}
 return $true
}
function Connect-Tunnel {
 $r=Read-Request
 if(-not (Parent-Alive $r)){throw 'Connection request expired'}
 $old=Read-State;if($null -ne $old -and $null -ne (Owned-Process $old)){throw 'Disconnect the existing tunnel first'}
 Ensure-Core
 if(Get-NetAdapter -Name $Interface -ErrorAction SilentlyContinue){throw 'Moondrop interface is already in use'}
 $config=Join-Path $Root 'config.json';Save-Text $config ((Configuration $r)|ConvertTo-Json -Depth 32 -Compress)
 & $Core check -c $config *> $null;if($LASTEXITCODE -ne 0){throw 'Engine rejected the protected configuration'}
 $first=if($r.Contains('entry') -and $null -ne $r.entry){$r.entry}else{$r.exit}
 if(-not (Parent-Alive $r)){throw 'Connection request expired'}
 if($r.strict){[MoondropWfp]::Apply($Core,$first.server,$first.server_port,0)}elseif([MoondropWfp]::Blocked()){throw 'Release the previous strict network block before non-strict connection'}
 if(-not (Parent-Alive $r)){throw 'Connection request expired'}
 $p=Start-Process -FilePath $Core -ArgumentList @('run','-c',('"'+$config+'"')) -WorkingDirectory $Root -WindowStyle Hidden -PassThru
 $s=@{pid=$p.Id;started=$p.StartTime.ToUniversalTime().Ticks.ToString();session=[guid]::NewGuid().ToString();strict=$r.strict;requestId=$r.requestId}
 if($r.Contains('parentPid')){$s.parentPid=$r.parentPid;$parent=Get-Process -Id $r.parentPid -ErrorAction Stop;$s.parentStarted=$parent.StartTime.ToUniversalTime().ToString('o')};if($r.Contains('sessionPath')){$s.sessionPath=$r.sessionPath}
 Save-Text $State ($s|ConvertTo-Json -Compress)
 try{
  $a=$null;for($i=0;$i -lt 200;$i++){$p.Refresh();if($p.HasExited -or -not (Parent-Alive $r)){throw 'Tunnel stopped before it was ready'};$a=Get-NetAdapter -Name $Interface -ErrorAction SilentlyContinue;if($null -ne $a -and $a.Status -eq 'Up'){break};Start-Sleep -Milliseconds 100}
  if($null -eq $a -or $a.Status -ne 'Up'){throw 'Tunnel interface did not start'}
  if($r.strict){[MoondropWfp]::Apply($Core,$first.server,$first.server_port,[uint32]$a.ifIndex)}
  # Start independent elevated watcher before reporting active.
  Start-Process -FilePath "$PSHOME\powershell.exe" -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$Installed+'"'),'-Action','Watch','-Session',$s.session) -WorkingDirectory $Root -WindowStyle Hidden |Out-Null
  Write-Status $true $false 'Full-device tunnel active' $r.requestId
 }catch{Stop-Core;throw}
}
try{
 if($Action -eq 'Validate'){$r=Read-Request;(Configuration $r)|ConvertTo-Json -Depth 32 -Compress;exit 0}
 if($Action -eq 'Status'){
  if(-not (Test-Path -LiteralPath $StatusFile)){'{"active":false,"blocked":false,"message":"Not installed"}';exit 0}
  $v=Convert-Map (Get-Content -LiteralPath $StatusFile -Raw|ConvertFrom-Json)
  if($v.active){
   $live=$false
   if($v.Contains('pid') -and $v.Contains('started')){try{$p=Get-Process -Id $v.pid -ErrorAction Stop;$live=([DateTimeOffset]::Parse($v.started).ToUnixTimeMilliseconds() -eq ([DateTimeOffset]$p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds())}catch{}}
   if(-not $live){$v.active=$false;$v.blocked=($v.Contains('strict') -and $v.strict -eq $true);$v.message='Tunnel stopped; check strict blocking or restore direct networking'}
  }
  try{Load-Wfp;$blocked=[MoondropWfp]::Blocked();if(-not $v.active){$v.blocked=$blocked}}catch{}
  $v|ConvertTo-Json -Compress;exit 0
 }
 Assert-Admin
 if($Action -eq 'Install'){
  if($ExpectedHash -notmatch '^[a-fA-F0-9]{64}$'){throw 'Helper installation integrity check failed'}
  if($null -eq $VerifiedSource -or $VerifiedSource.Length -eq 0){$VerifiedSource=[IO.File]::ReadAllBytes($PSCommandPath)}
  $sha=[Security.Cryptography.SHA256]::Create();try{$actual=([BitConverter]::ToString($sha.ComputeHash($VerifiedSource))).Replace('-','')}finally{$sha.Dispose()}
  if($actual -ne $ExpectedHash){throw 'Helper installation integrity check failed'}
  Initialize-Root
  Save-Private $Installed $VerifiedSource -PublicRead
  Assert-Protected $Installed
  # Installation/update must not erase a previous strict recovery state. The
  # read-only Status action reconciles a saved active PID after app crash/reboot.
  if(Test-Path -LiteralPath $StatusFile){Assert-Protected $StatusFile;Get-Content -LiteralPath $StatusFile -Raw}
  else{Load-Wfp;Write-Status $false ([MoondropWfp]::Blocked()) 'Tunnel helper installed'}
  exit 0
 }
 Initialize-Root;Assert-Protected $Installed
 if($PSCommandPath -and [IO.Path]::GetFullPath($PSCommandPath) -ne [IO.Path]::GetFullPath($Installed)){throw 'Run the administrator-owned installed helper'}
 Load-Wfp
 if($Action -eq 'Watch'){
  while($true){$s=Read-State;if($null -eq $s -or $s.session -ne $Session){exit 0};if($null -eq (Owned-Process $s) -or -not (Parent-Alive $s)){$guard=New-Object Threading.Mutex($false,'Global\MoondropTunnelBroker');try{if($guard.WaitOne(10000)){try{$current=Read-State;if($null -ne $current -and $current.session -eq $Session){Stop-Core;Write-Status $false ([MoondropWfp]::Blocked()) 'Tunnel stopped; strict blocking is retained until Release' $s.requestId}}finally{$guard.ReleaseMutex()}}}finally{$guard.Dispose()};exit 0};Start-Sleep -Seconds 1}
 }
 $mutex=New-Object Threading.Mutex($false,'Global\MoondropTunnelBroker')
 if(-not $mutex.WaitOne(0)){throw 'Another tunnel operation is in progress'}
 try{
  switch($Action){
   'Connect'{Connect-Tunnel}
   'Disconnect'{Stop-Core;Write-Status $false ([MoondropWfp]::Blocked()) 'Disconnected; use Release to restore direct networking'}
   'Release'{Stop-Core;[MoondropWfp]::Clear;Write-Status $false $false 'Direct networking restored'}
  }
 }finally{$mutex.ReleaseMutex();$mutex.Dispose()}
}catch{
 # Validation is strictly side-effect-free, including malformed input errors.
 if($Action -eq 'Validate'){Write-Error 'Moondrop profile validation failed.';exit 1}
 # Never release strict filters on an error or process exit.
 $blocked=$false;try{Load-Wfp;$blocked=[MoondropWfp]::Blocked()}catch{}
 try{Write-Status $false $blocked 'Tunnel operation failed. Strict blocking, if enabled, is retained.'}catch{}
 # Parser/native exceptions can contain request text; never print credentials.
 Write-Error 'Moondrop tunnel operation failed: invalid profile, permissions or engine setup. Strict blocking is retained.';exit 1
}
