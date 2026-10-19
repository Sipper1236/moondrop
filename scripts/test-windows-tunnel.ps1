[CmdletBinding()]
param([switch]$FirewallIntegration,[switch]$InstallationIntegration)
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'windows-tunnel-helper.ps1'
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($helper,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors|Out-String)}
# Compile the actual native implementation and verify the 64-bit Windows ABI.
$assignment=$ast.Find({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$WfpSource'},$true)
$source=$assignment.Right.Expression.Value
Add-Type -TypeDefinition $source
if(([MoondropWfp]::Sizes() -join ',') -ne '16,40,200,72'){throw ('Native WFP ABI mismatch: '+([MoondropWfp]::Sizes() -join ','))}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('moondrop-tunnel-test-'+[guid]::NewGuid())
[void][IO.Directory]::CreateDirectory($temp)
try{
 if(-not $env:ProgramData){$env:ProgramData=$temp}
 $path=Join-Path $temp 'request.json'
 $base=@{exit=@{type='shadowsocks';server='1.1.1.1';server_port=443;method='aes-256-gcm';password='fixture'};strict=$true;requestId='fixture'}
 function Validate($r,[bool]$Pass){
  [IO.File]::WriteAllText($path,($r|ConvertTo-Json -Depth 20 -Compress))
  $shell=(Get-Process -Id $PID).Path
  $psi=New-Object Diagnostics.ProcessStartInfo
  $psi.FileName=$shell;$psi.Arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$helper+'" -Action Validate -RequestPath "'+$path+'"'
  $psi.UseShellExecute=$false;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
  $child=New-Object Diagnostics.Process;$child.StartInfo=$psi
  try{[void]$child.Start();$output=$child.StandardOutput.ReadToEnd();$errorText=$child.StandardError.ReadToEnd();$child.WaitForExit();$code=$child.ExitCode}finally{$child.Dispose()}
  if(($code -eq 0) -ne $Pass){throw "Profile validation expectation failed: $output $errorText"}
  if($Pass){$cfg=($output|Out-String)|ConvertFrom-Json;if(-not $cfg.inbounds[0].auto_route -or -not $cfg.inbounds[0].strict_route -or $cfg.route.final -ne 'proxy' -or $cfg.dns.servers[0].detour -ne 'proxy'){throw 'Generated configuration may bypass proxy'};if($cfg.inbounds[0].address.Count -ne 2){throw 'Dual-stack tunnel missing'}}
 }
 Validate $base $true
 foreach($ip in @('127.0.0.1','192.168.1.1','169.254.169.254','::1','::ffff:127.0.0.1','fe80::1','fc00::1','0.0.0.0','224.0.0.1','100.64.0.1','198.18.0.1','2001:db8::1','2002::1','localhost','1.1.1.1%1')){$base.exit.server=$ip;Validate $base $false}
 $base.exit.server='2606:4700:4700::1111';Validate $base $true
 $base.exit.server='1.1.1.1';$base.exit.method='rc4-md5';Validate $base $false
 $base.exit.method='aes-256-gcm';$base.exit.plugin='cmd.exe';Validate $base $false;$base.exit.Remove('plugin')
 $base.exit=@{type='trojan';server='1.1.1.1';server_port=443;password='fixture';tls=@{enabled=$true;server_name='example.com';insecure=$true}};Validate $base $false
 $base.exit.tls.insecure=$false;Validate $base $true
 $base.exit.tls.certificate_path='C:\Windows\secret';Validate $base $false
 # Actual atomic owner/security-descriptor creation is Windows-specific.
 if($InstallationIntegration){
  if($env:OS -ne 'Windows_NT'){throw 'Installation integration requires Windows'}
  $common=[Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
  $installRoot=Join-Path $common 'Moondrop\Tunnel';$installParent=Split-Path $installRoot
  if(Test-Path -LiteralPath $installParent){throw 'Refusing installation test on an existing Moondrop store'}
  $ownsStore=$false
  try{
   $installChild=Join-Path $temp 'install.ps1'
   [IO.File]::WriteAllText($installChild,'param($Source);$bytes=[IO.File]::ReadAllBytes($Source);$hash=(Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash;& ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString($bytes))) -Action Install -ExpectedHash $hash -VerifiedSource $bytes')
   & (Get-Process -Id $PID).Path -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installChild $helper
   if($LASTEXITCODE -ne 0){throw 'Verified snapshot installation failed'};$ownsStore=$true
   foreach($name in @('Assert-Protected','Save-Private','Save-Text')){$f=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true);. ([ScriptBlock]::Create($f.Extent.Text))}
   $fixture=Join-Path $installRoot 'credential-fixture.json';Save-Text $fixture '{"password":"test-fixture-only"}'
   foreach($file in @($installRoot,(Join-Path $installRoot 'windows-tunnel-helper.ps1'),(Join-Path $installRoot 'status.json'),$fixture)){
    Assert-Protected $file
    $acl=Get-Acl -LiteralPath $file
    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne 'S-1-5-32-544'){throw 'Privileged object owner is not Administrators'}
   }
   # Reinstallation must retain strict recovery metadata even if the app dies
   # before Connect; no live filters are needed for this persistence regression.
   $statusPath=Join-Path $installRoot 'status.json'
   Save-Text $statusPath '{"active":false,"blocked":true,"strict":true,"requestId":"previous-session","message":"Strict recovery fixture"}' -PublicRead
   & (Get-Process -Id $PID).Path -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installChild $helper
   if($LASTEXITCODE -ne 0){throw 'Helper reinstallation failed'}
   $retained=Get-Content -LiteralPath $statusPath -Raw|ConvertFrom-Json
   if(-not $retained.blocked -or $retained.requestId -ne 'previous-session'){throw 'Helper installation erased strict recovery state'}
   $acl=Get-Acl -LiteralPath $fixture
   foreach($a in $acl.Access){if($a.AccessControlType -eq 'Allow' -and $a.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @('S-1-5-18','S-1-5-32-544')){throw 'Credential fixture readable by ordinary users'}}
  }finally{if($ownsStore -and (Test-Path -LiteralPath $installParent)){Remove-Item -LiteralPath $installParent -Recurse -Force}}
 }
 # Native WFP execution deliberately requires an explicit disposable Windows runner.
 if($FirewallIntegration){
  if($env:OS -ne 'Windows_NT'){throw 'Firewall integration requires Windows'}
  if(-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Disposable runner must be elevated'}
  if([MoondropWfp]::Blocked()){throw 'Refusing to replace an existing Moondrop policy'}
  function Can-Tcp($Address,$Port){$s=New-Object Net.Sockets.TcpClient;try{$task=$s.ConnectAsync($Address,$Port);if(-not $task.Wait(3000)){return $false};return $s.Connected}catch{return $false}finally{$s.Dispose()}}
  if(-not (Can-Tcp '1.1.1.1' 443)){throw 'Runner lacks public network baseline'}
  $captureRoot=if($env:MOONDROP_WINDOWS_CAPTURE_DIR){$env:MOONDROP_WINDOWS_CAPTURE_DIR}else{Join-Path $temp 'nic-captures'}
  [void][IO.Directory]::CreateDirectory($captureRoot)
  $route=Get-NetRoute -DestinationPrefix '0.0.0.0/0' |Sort-Object RouteMetric|Select-Object -First 1
  $resolver=(Get-DnsClientServerAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4).ServerAddresses |Where-Object {$_ -notmatch '^127\.'}|Select-Object -First 1
  if(-not $resolver){throw 'No configured IPv4 DNS resolver baseline available'}
  function Capture-Dns($Phase,$Id){
   $etl=Join-Path $captureRoot ($Phase+'.etl');$pcap=Join-Path $captureRoot ($Phase+'.pcapng')
   & "$env:WINDIR\System32\pktmon.exe" filter remove |Out-Null
   & "$env:WINDIR\System32\pktmon.exe" filter add MoondropDns -i $resolver -t UDP -p 53 |Out-Null
   if($LASTEXITCODE -ne 0){throw 'Cannot configure NIC DNS capture'}
   & "$env:WINDIR\System32\pktmon.exe" start --capture --comp nics --pkt-size 0 --file-name $etl |Out-Null
   if($LASTEXITCODE -ne 0){throw 'Cannot start NIC DNS capture'}
   $sent=-1;$reply=$false;$socketError=''
   try{
    $udp=New-Object Net.Sockets.UdpClient
    try{
     $udp.Client.ReceiveTimeout=3000
     [byte[]]$query=@([byte]($Id -shr 8),[byte]($Id -band 255),1,0,0,1,0,0,0,0,0,0,7,101,120,97,109,112,108,101,3,99,111,109,0,0,1,0,1)
     try{$udp.Connect($resolver,53);$sent=$udp.Send($query,$query.Length);$remote=New-Object Net.IPEndPoint([Net.IPAddress]::Any,0);$answer=$udp.Receive([ref]$remote);$reply=($answer.Length -ge 12 -and $answer[0] -eq $query[0] -and $answer[1] -eq $query[1] -and ($answer[2] -band 128) -ne 0)}catch [Net.Sockets.SocketException]{$socketError=$_.Exception.SocketErrorCode.ToString()}
    }finally{$udp.Dispose()}
    Start-Sleep -Milliseconds 500
   }finally{& "$env:WINDIR\System32\pktmon.exe" stop |Out-Null}
   & "$env:WINDIR\System32\pktmon.exe" etl2pcap $etl --out $pcap |Out-Null
   if($LASTEXITCODE -ne 0){throw 'Cannot convert NIC DNS capture'}
   $analysis=& python (Join-Path $PSScriptRoot 'inspect-windows-udp-capture.py') $pcap $resolver $Id
   if($LASTEXITCODE -ne 0){throw 'NIC DNS capture could not be inspected safely'}
   $evidence=$analysis|ConvertFrom-Json
   $result=@{phase=$Phase;sent=$sent;reply=$reply;socketError=$socketError;nicQueryFrames=$evidence.matching_dns_query_frames;nicPackets=$evidence.packets;dnsId=$Id;resolver=$resolver}
   $result|ConvertTo-Json -Compress|Tee-Object -FilePath (Join-Path $captureRoot ($Phase+'.json'))|Write-Host
   return $result
  }
  $baselineId=Get-Random -Minimum 1024 -Maximum 30000
  $baseline=Capture-Dns 'baseline' $baselineId
  if(-not $baseline.reply -or $baseline.nicQueryFrames -lt 1){throw 'DNS baseline needs valid resolver response and independently observed NIC query'}
  $recovery=$null
  try{
   # Independent timed recovery is armed BEFORE filters, so even an assertion,
   # test-host crash or Ctrl+C cannot strand this disposable runner indefinitely.
   $sourceFile=Join-Path $temp 'wfp.cs';[IO.File]::WriteAllText($sourceFile,$source)
   $ready=Join-Path $temp 'recovery.ready';$recoveryScript=Join-Path $temp 'recovery.ps1'
   [IO.File]::WriteAllText($recoveryScript,'param($Source,$Ready);Add-Type -Path $Source;[IO.File]::WriteAllText($Ready,"ready");Start-Sleep -Seconds 90;[MoondropWfp]::Clear()')
   $recovery=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$recoveryScript+'"'),('"'+$sourceFile+'"'),('"'+$ready+'"')) -PassThru
   for($i=0;$i -lt 300 -and -not (Test-Path -LiteralPath $ready);$i++){if($recovery.HasExited){throw 'Timed firewall recovery failed to arm'};Start-Sleep -Milliseconds 100}
   if(-not (Test-Path -LiteralPath $ready)){throw 'Timed firewall recovery did not acknowledge readiness'}
   # Child creates PERSISTENT policy then exits; parent must remain blocked.
   $sourceFile=Join-Path $temp 'wfp.cs';[IO.File]::WriteAllText($sourceFile,$source)
   $child=Join-Path $temp 'child.ps1';[IO.File]::WriteAllText($child,'param($Source,$Core);Add-Type -Path $Source;[MoondropWfp]::Apply($Core,"1.1.1.1",443,0)')
   & (Get-Process -Id $PID).Path -NoProfile -File $child $sourceFile "$env:WINDIR\System32\svchost.exe"
   if($LASTEXITCODE -ne 0 -or -not [MoondropWfp]::Blocked()){throw 'Strict policy did not survive policy process exit'}
   if(Can-Tcp '1.1.1.1' 443){throw 'IPv4 traffic escaped strict policy'}
   if(Can-Tcp '2606:4700:4700::1111' 443){throw 'IPv6 traffic escaped strict policy'}
   # Winsock send completion is not a delivery acknowledgement. A fresh socket
   # and unique valid DNS ID distinguish real NIC egress from an async drop.
   $strict=Capture-Dns 'strict' ($baselineId+30000)
   if($strict.nicQueryFrames -ne 0 -or $strict.reply){throw 'DNS UDP query escaped strict policy at NIC capture point'}
   $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);try{$listener.Start();if(-not (Can-Tcp '127.0.0.1' $listener.LocalEndpoint.Port)){throw 'Loopback exception failed'}}finally{$listener.Stop()}
  }finally{[MoondropWfp]::Clear();if($null -ne $recovery -and -not $recovery.HasExited){Stop-Process -Id $recovery.Id -Force}}
  if([MoondropWfp]::Blocked() -or -not (Can-Tcp '1.1.1.1' 443)){throw 'Release did not restore networking'}
 }
 Write-Output 'Windows tunnel: syntax, native ABI, public endpoint validation and configuration checks passed.'
}finally{Remove-Item -LiteralPath $temp -Recurse -Force}
