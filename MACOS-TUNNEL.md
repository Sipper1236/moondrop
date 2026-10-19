# macOS Full-device Tunnel

Settings → Privacy & routing → Full-device Tunnel enables the sing-box `utun`
backend. Application Proxy Mode remains available without administrator access.
The tunnel carries IPv4, IPv6, TCP and UDP through the selected encrypted server.
DNS uses HTTPS through that proxy, and the broker temporarily sets active network
services to the tunnel DNS addresses. Two-hop uses a real Shadowsocks entry
`detour`, with a distinct TCP-capable exit.

The first connection requests macOS administrator authorization. This installs a
bounded helper under `/Library/PrivilegedHelperTools/` and independently downloads
a pinned sing-box 1.14.3 engine for Intel or Apple Silicon. The archive SHA-256 is
embedded in the helper; app-provided executables, arbitrary sing-box configuration,
file-backed credentials and arbitrary outbound detours are not accepted.

Python 3 is required for the administrator broker. macOS does not ship a usable
Python installation on every machine. Use Apple's developer tools Python or a
root-owned Python.org framework installation. A user-writable Homebrew Python is
not elevated. Java invokes the selected interpreter with `-I -S`, excluding user
site packages, site startup hooks and `PYTHONPATH` from the privileged process.

## Strict disconnect blocking

Enable **Strict mode** to install an independent PF rule set. It permits loopback,
the reserved tunnel interface, root-owned traffic to the selected entry endpoint,
DHCP and IPv6 neighbor/router discovery. Other outbound IPv4/IPv6 traffic,
including direct DNS and UDP, is blocked. This remains installed if sing-box, Java
or the broker crashes. Selecting a different server atomically replaces the
endpoint exception. Disabling the checkbox does not remove an already active
strict block; use **Restore direct internet** explicitly.

Strict mode requires the stock macOS root PF filter rule:

```
anchor "com.apple/*" all
```

Moondrop installs its rules in `com.apple/moondrop`. It refuses modified root PF
layouts or other populated `com.apple` filtering anchors that could bypass its
block, and refuses to claim an occupied anchor.
It never flushes the host's rules, edits `/etc/pf.conf`, or disables another
program's PF enable reference. Existing PF connection states are invalidated when
strict mode is activated or reconnected: old states otherwise bypass new rules.
This interrupts existing connections on purpose. Existing VPNs, security software
and customized PF rules may be incompatible; Application Proxy Mode remains usable.

Loopback is allowed for local applications. The root-only entry endpoint is a
narrow exception required by the engine; another root process could also use that
same endpoint. This does not protect against administrator malware. The block is
not persistent across reboot, which removes the kernel PF state.

## Cleanup and recovery

Normal disconnect stops the engine and restores original DNS settings, but leaves
strict blocking active. Closing/crashing Java is detected by the independent
broker and stops the engine. DNS snapshots survive a broker crash; on the next
run or explicit recovery they are restored only when the current values are
still the values set by Moondrop. User or other VPN changes are not overwritten.
Any failed restoration remains in the root recovery journal for a retry.

Use **Restore direct internet** in Moondrop to remove its PF rules and restore
its saved DNS settings. Terminal recovery, using the same trusted interpreter:

```
sudo /usr/bin/python3 -I -S /Library/PrivilegedHelperTools/xyz.moondrop.tunnel.py --release
```

If using the Python.org framework, substitute its root-owned executable path.
Recovery never flushes unrelated PF anchors. Recovery requires administrator
authorization because removing a strict block is a privileged networking change.

## Broker protocol and validation

`--serve SESSION_DIR UID OWNER_PID` locks the root broker, validates that the
application process belongs to `UID`, and holds a descriptor for its private
0700 session directory. The app keeps a regular, user-owned `alive` marker in that directory for the
session lifetime. Removing it cancels pending startup, including a slow engine
download, and triggers cleanup while retaining any strict block. The app
atomically replaces `request.json` with
`{"op":"start","strict":true,"exit":PROFILE,"entry":null,"requestId":"unique"}`.
Other operations are `stop` (retain strict block) and `release` (explicitly remove
it). `status.json` acknowledges `requestId` and reports `active`, `blocked`,
`message`, `brokerPid`, and optional `error`. Credentials never appear in status.
Requests are bounded, checked for ownership, and opened without following
symlinks. A publicly readable, root-controlled summary is also stored at
`/var/run/moondrop-tunnel/status.json` for recovery after app restart.

Profiles require pinned public IP endpoints, authenticated Shadowsocks ciphers
or verified TLS protected protocols. Root builds its own fixed TUN/DNS/routing
configuration. The app cannot ask the helper to execute commands or alter any
other files, routes or firewall rules.

## Verification and current limits

Run `python3 scripts/test-macos-tunnel.py` for offline policy, hostile-input,
DNS-restoration and IPC tests. On macOS CI, native smoke tests additionally check
both the actual pinned engine's configuration parser and PF's syntax parser,
without loading firewall rules or altering host routing.

The implementation was developed on Linux. End-to-end macOS tunnel traffic,
physical-network changes, engine/app crash leak behavior and recovery still need
validation on a macOS machine before treating this mode as production verified.
A live test must run on a dedicated macOS machine/VM because PF and routing are
system wide. No test in the default suite changes the host's live firewall or DNS.

References: [sing-box TUN](https://sing-box.sagernet.org/configuration/inbound/tun/),
[pfctl manual](https://www.manpagez.com/man/8/pfctl/),
[networksetup manual](https://www.manpagez.com/man/8/networksetup/).
