# Linux full-device mode

Full-device mode is optional and currently available on Linux x64. Application Proxy Mode remains the simple, unprivileged default. Full-device mode requires PolicyKit (`pkexec`), Python 3, iproute2, nftables, and `/dev/net/tun`.

On first activation Moondrop asks for administrator authorization to install its narrow broker at `/usr/local/libexec/moondrop-tunnel-helper.py`. The broker independently downloads the official sing-box 1.14.3 Linux x64 archive, checks the pinned SHA-256, and installs the engine under `/usr/local/lib/moondrop`. It does not run a user-supplied engine as root. It accepts bounded JSON with public IP addresses and a strict allowlist of encrypted outbound settings; it never accepts shell commands, file paths, certificate paths or arbitrary sing-box configuration.

The broker owns a gVisor TUN, encrypted DNS-over-HTTPS through the selected proxy, a localhost health-check proxy, routing table 20883 and rule priorities 18083–18093. It rejects preexisting routing collisions. For two-hop connections the exit really detours through the distinct entry. Current full-device support requires public endpoints, even for manually added profiles.

With strict protection enabled, an independent nftables output firewall permits loopback, the TUN, the privileged engine's exact entry address and port, DHCP and limited IPv6 neighbor discovery. IPv4, IPv6, UDP and direct DNS otherwise fail closed. There is no general established-connection or root-user bypass. Ordinary applications cannot directly use the entry exception. Stopping or crashing the tunnel keeps the firewall in place; stopping the app is not consent to restore direct internet. Reconnect uses locally cached literal endpoint addresses instead of doing direct DNS while blocked. A new hostname without a saved address requires explicitly restoring internet first.

Use **Restore direct internet** in Settings to remove strict protection. If the UI is unavailable, use:

```
pkexec /usr/bin/python3 /usr/local/libexec/moondrop-tunnel-helper.py --release
```

That command stops only the broker's recorded engine (checking PID start time and executable), removes only its owned routing state and nftables table, and marks the tunnel inactive. Non-secret status is `/run/moondrop-tunnel/status.json`; configuration and credentials are root-readable only and removed on stop. No background service or boot firewall is installed: strict protection persists through tunnel/app crashes, but a machine reboot clears the transient `/run` state and kernel firewall. Root or other software with network-administration privileges can change these rules.

## Offline regression tests

No host routing, firewall, system proxy or existing tunnel service should be changed during tests. Run policy validation alone:

```
python3 scripts/test-linux-tunnel.py
```

Run actual kernel firewall and sing-box TUN routing checks inside a fresh user/network namespace:

```
MOONDROP_TEST_HOST_NET="$(readlink /proc/self/ns/net)" \
MOONDROP_TUN_TEST_CORE=/path/to/verified/sing-box \
unshare -Urn -- python3 scripts/test-linux-tunnel.py --namespace
```

The namespace has no path to the internet. Controlled TUN devices stand in for physical links. The real engine test sends an ordinary application socket through the actual TUN and encrypted Shadowsocks fixture, then kills the engine and checks that direct traffic remains blocked. Other tests inspect IPv4/IPv6 TCP/UDP/DNS packets on the fake network interface, verify exact-entry exceptions, remove the TUN, explicitly restore networking, and atomically replace the allowed entry.

The single-UID unprivileged namespace cannot become a second user. CI additionally runs these checks using `sudo unshare -n`, where a child drops to UID 65534 to prove that an ordinary application cannot directly contact the privileged entry exception. Tests refuse to run kernel checks without proof that their network namespace differs from the parent.
