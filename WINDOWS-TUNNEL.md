# Windows full-device tunnel

Experimental: implementation and automated Windows checks are provided; native
end-to-end tunnel behavior has not been verified on the Linux development host.

Moondrop's optional **Full-device tunnel** creates a dual-stack Wintun interface
through sing-box. It requires Windows 10/11 x64 or ARM64 and administrator consent.
Application Proxy Mode remains available without elevation. This helper uses
Windows PowerShell 5.1 (the inbox 64-bit version), not an arbitrary PATH executable.

The broker is installed under the operating system's CommonApplicationData known
folder, normally `C:\ProgramData\Moondrop\Tunnel`. Java verifies an in-memory
snapshot of the bundled helper before elevated installation. The installed helper,
engine and configuration are writable only by Administrators and SYSTEM. Ancestor
junctions and unsafe ACLs are refused. Only the helper and sanitized status are
readable by normal users; proxy credentials and saved process state are private.
The engine comes from the pinned official sing-box 1.14.3 archive with a compiled-in
SHA-256 digest for each supported architecture. No caller-supplied executable,
DLL, DNS, route, file include, script or output path is accepted.

## Routing and strict mode

The helper generates the entire engine configuration. IPv4 and IPv6 use the TUN,
port 53 is hijacked into encrypted DNS over the exit proxy, and TCP and UDP use
the selected encrypted outbound. Endpoint DNS resolution must occur before the
request: the broker accepts only pinned globally routable literal IPs and preserves
TLS server names independently. An optional Shadowsocks entry can carry a distinct
TCP exit. QUIC exits are deliberately rejected for this two-hop implementation.

**Strict mode** adds persistent native Windows Filtering Platform (WFP) filters at
the IPv4 and IPv6 ALE connection and receive/accept layers, in a dedicated owned
sublayer. High-weight exceptions allow the tunnel interface, loopback, and only
the protected sing-box executable talking to the selected entry IP and port.
A low-weight hard block rejects other direct connections. These are WFP filters,
not a broad Windows Firewall block rule with ineffective overlapping allow rules.
The exceptions are soft permits, so another administrator's firewall blocks still
apply. Essential DHCP and link-local IPv6 neighbour/router control are allowed;
there is no general LAN or plaintext DNS exception.

Filters outlive sing-box, PowerShell and Moondrop. The watchdog notices app-session
cancellation, app exit or engine exit, stops the owned engine and retains strict
blocking. **Disconnect** retains the block; **Restore direct networking/Release**
explicitly deletes only Moondrop's fixed filter keys and sublayer. Rebooting does
not intentionally release a strict block, but protection before Windows Base
Filtering Engine starts during boot has not been established. Non-strict mode has ordinary OS routing
fallback after the tunnel is lost and must not be described as leak protection.

Windows' own networking and existing administrator policy can prevent a tunnel
from starting. An unexpected interface-name collision is rejected. The broker
does not disable other firewall rules or change global firewall profiles.

## Broker interface

All privileged operations use the installed helper and UAC. `Status` is read-only.

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'C:\ProgramData\Moondrop\Tunnel\windows-tunnel-helper.ps1' -Action Connect -RequestPath request.json
# Stop core, retain strict block:
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'C:\ProgramData\Moondrop\Tunnel\windows-tunnel-helper.ps1' -Action Disconnect
# Explicit recovery, with administrator consent:
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'C:\ProgramData\Moondrop\Tunnel\windows-tunnel-helper.ps1' -Action Release
```

A request is a regular JSON file limited to 64 KiB:

```json
{"op":"start","requestId":"unique-request-id","strict":true,"parentPid":1234,"sessionPath":"C:\\Users\\you\\AppData\\Local\\Moondrop\\tunnel-session-marker","exit":{"type":"shadowsocks","server":"1.1.1.1","server_port":443,"method":"aes-256-gcm","password":"example-only"}}
```

`entry` is optional. `sessionPath` is an existence-only marker; the elevated helper
never writes to it. The broker captures the parent process start time itself to
avoid PID-reuse errors. `parentStarted` can optionally be supplied as an ISO 8601
instant. Cancellation deletes the session marker. The fixed `status.json` contains
`active`, `blocked`, `message`, `requestId`, and the core `pid` when active, never
profile credentials. Local health proxy: `127.0.0.1:17890`.

## Validation and limits

Run `scripts/test-windows-tunnel.ps1` for parser, actual C# compilation/native ABI,
malicious endpoint and generated routing/DNS checks. On a **disposable elevated
Windows runner only**, add `-FirewallIntegration` to test filter admission, strict
blocking after the filter-creating process exits, IPv4/IPv6 connection attempts,
UDP DNS, loopback and explicit recovery. CI runs both using Windows Server 2022.
The separate `-InstallationIntegration` check exercises verified-snapshot helper
installation and actual Windows admin ownership/private file ACL creation in a
fresh fixed store. It refuses an existing store.
The integration test briefly blocks the runner's direct traffic, arms a separate
90-second cleanup process before installing filters, and always tries to remove
the owned filters in `finally`; do not run it on a personal machine.

Linux can run the non-firewall checks with PowerShell 7, but that does not prove
Windows WFP admission, Wintun routing, proxy reachability or end-to-end DNS/IPv6
leak behavior. Full native tunnel testing requires a reachable fixture proxy on
Windows; macOS behavior is separate. Installer Authenticode signing is not
provided by this helper and still needs a publisher certificate.

The implementation follows [sing-box TUN configuration](https://sing-box.sagernet.org/configuration/inbound/tun/),
[WFP filtering conditions](https://learn.microsoft.com/en-us/windows/win32/fwp/filtering-conditions-available-at-each-filtering-layer),
[WFP filter arbitration](https://learn.microsoft.com/en-us/windows/win32/fwp/filter-arbitration),
and [persistent WFP operation](https://learn.microsoft.com/en-us/windows/win32/fwp/basic-operation).
The reason ordinary broad firewall block rules cannot express these exceptions is
[Windows Firewall rule precedence](https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/rules).
