// SPDX-License-Identifier: GPL-3.0-or-later
// Package nativebridge provides a narrow Android binding to official sing-box.
package nativebridge

import (
	"encoding/json"
	"errors"
	"net/netip"
	"sync"

	"github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/sagernet/sing-box/include"
)

// AndroidPlatform is implemented by the application's VpnService adapter.
// OpenTun returns a borrowed descriptor: Java retains ownership until Stop.
type AndroidPlatform interface {
	Protect(fd int32) bool
	OpenTun() (int32, error)
	InterfacesJSON() string
	DefaultInterfaceName() string
	DefaultInterfaceIndex() int32
	DefaultInterfaceMetered() bool
	Lookup(domain, network string) (string, error)
}

var state struct {
	sync.Mutex
	server   *libbox.CommandServer
	platform *platform
}

// Start validates and starts the configuration. A mixed-only configuration can
// test an encrypted outbound without creating a device VPN.
func Start(config, files, cache string, android AndroidPlatform) error {
	state.Lock()
	defer state.Unlock()
	if state.server != nil {
		return errors.New("an engine is already running")
	}
	if android == nil {
		return errors.New("Android platform is required")
	}
	if err := libbox.Setup(&libbox.SetupOptions{BasePath: files, WorkingPath: files,
		TempPath: cache, FixAndroidStack: true, LogMaxLines: 50,
		OomKillerDisabled: true, CrashReportSource: "moondrop"}); err != nil {
		return err
	}
	p := &platform{android: android}
	server, err := libbox.NewCommandServer(p, p)
	if err != nil {
		return err
	}
	// No management socket is opened; all control stays inside the app process.
	if err = server.StartOrReloadService(config, &libbox.OverrideOptions{}); err != nil {
		server.CloseService()
		server.Close()
		return err
	}
	state.server, state.platform = server, p
	return nil
}

func Stop() {
	state.Lock()
	defer state.Unlock()
	if state.server != nil {
		state.server.CloseService()
		state.server.Close()
		state.server, state.platform = nil, nil
	}
}

// UpdateNetwork handles Wi-Fi/mobile handovers and loss of connectivity.
func UpdateNetwork(name string, index int32, metered bool) {
	state.Lock()
	p := state.platform
	state.Unlock()
	if p != nil {
		p.update(name, index, metered)
	}
}

type platform struct {
	android  AndroidPlatform
	mu       sync.Mutex
	listener libbox.InterfaceUpdateListener
}

func (p *platform) update(name string, index int32, metered bool) {
	p.mu.Lock()
	listener := p.listener
	p.mu.Unlock()
	if listener != nil {
		listener.UpdateDefaultInterface(name, index, metered, false)
	}
}
func (p *platform) LocalDNSTransport() libbox.LocalDNSTransport { return p }
func (p *platform) Raw() bool                                   { return false }
func (p *platform) Lookup(ctx *libbox.ExchangeContext, network, domain string) error {
	addresses, err := p.android.Lookup(domain, network)
	if err != nil {
		return err
	}
	ctx.Success(addresses)
	return nil
}
func (p *platform) Exchange(*libbox.ExchangeContext, []byte) error { return errors.ErrUnsupported }
func (p *platform) UsePlatformAutoDetectInterfaceControl() bool    { return true }
func (p *platform) AutoDetectInterfaceControl(fd int32) error {
	if !p.android.Protect(fd) {
		return errors.New("could not protect outbound socket from VPN routing")
	}
	return nil
}
func (p *platform) OpenTun(_ libbox.TunOptions) (int32, error) { return p.android.OpenTun() }
func (p *platform) UseProcFS() bool                            { return false }
func (p *platform) FindConnectionOwner(int32, string, int32, string, int32) (*libbox.ConnectionOwner, error) {
	return nil, errors.ErrUnsupported
}
func (p *platform) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	p.mu.Lock()
	p.listener = listener
	p.mu.Unlock()
	p.update(p.android.DefaultInterfaceName(), p.android.DefaultInterfaceIndex(), p.android.DefaultInterfaceMetered())
	return nil
}
func (p *platform) CloseDefaultInterfaceMonitor(libbox.InterfaceUpdateListener) error {
	p.mu.Lock()
	p.listener = nil
	p.mu.Unlock()
	return nil
}

type interfaceDescription struct {
	Index     int32    `json:"index"`
	MTU       int32    `json:"mtu"`
	Name      string   `json:"name"`
	Flags     int32    `json:"flags"`
	Type      int32    `json:"type"`
	Addresses []string `json:"addresses"`
	DNS       []string `json:"dns"`
	Gateways  []string `json:"gateways"`
	Metered   bool     `json:"metered"`
}

func (p *platform) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	var descriptions []interfaceDescription
	if err := json.Unmarshal([]byte(p.android.InterfacesJSON()), &descriptions); err != nil {
		return nil, err
	}
	result := &interfaces{}
	for _, it := range descriptions {
		for _, address := range it.Addresses {
			if _, err := netip.ParsePrefix(address); err != nil {
				return nil, err
			}
		}
		result.values = append(result.values, &libbox.NetworkInterface{Index: it.Index, MTU: it.MTU,
			Name: it.Name, Flags: it.Flags, Type: it.Type, Addresses: &strings{values: it.Addresses},
			DNSServer: &strings{values: it.DNS}, Gateway: &strings{values: it.Gateways}, Metered: it.Metered})
	}
	return result, nil
}

type interfaces struct{ values []*libbox.NetworkInterface }

func (i *interfaces) HasNext() bool { return len(i.values) > 0 }
func (i *interfaces) Next() *libbox.NetworkInterface {
	v := i.values[0]
	i.values = i.values[1:]
	return v
}

type strings struct{ values []string }

func (i *strings) Len() int32                                   { return int32(len(i.values)) }
func (i *strings) HasNext() bool                                { return len(i.values) > 0 }
func (i *strings) Next() string                                 { v := i.values[0]; i.values = i.values[1:]; return v }
func (p *platform) UnderNetworkExtension() bool                 { return false }
func (p *platform) IncludeAllNetworks() bool                    { return false }
func (p *platform) ReadWIFIState() *libbox.WIFIState            { return nil }
func (p *platform) ClearDNSCache()                              {}
func (p *platform) SendNotification(*libbox.Notification) error { return nil }
func (p *platform) CancelNotification(string, int32) error      { return nil }
func (p *platform) StartNeighborMonitor(libbox.NeighborUpdateListener) error {
	return errors.ErrUnsupported
}
func (p *platform) CloseNeighborMonitor(libbox.NeighborUpdateListener) error { return nil }
func (p *platform) RegisterMyInterface(string)                               {}
func (p *platform) UsePlatformShell() bool                                   { return false }
func (p *platform) CheckPlatformShell() error                                { return errors.ErrUnsupported }
func (p *platform) OpenShellSession(*libbox.PlatformUser, string, libbox.StringIterator, string, int32, int32) (libbox.ShellSession, error) {
	return nil, errors.ErrUnsupported
}
func (p *platform) LookupUser(string) (*libbox.PlatformUser, error) {
	return nil, errors.ErrUnsupported
}
func (p *platform) LookupSFTPServer() (string, error)     { return "", errors.ErrUnsupported }
func (p *platform) ReadSystemSSHHostKey() (string, error) { return "", errors.ErrUnsupported }
func (p *platform) TailscaleHostname() string             { return "Moondrop" }
func (p *platform) UsePlatformBridge() bool               { return false }
func (p *platform) CreateBridge(*libbox.BridgeOptions) (libbox.BridgeSession, error) {
	return nil, errors.ErrUnsupported
}
func (p *platform) ServiceStop() error   { return nil }
func (p *platform) ServiceReload() error { return errors.ErrUnsupported }
func (p *platform) GetSystemProxyStatus() (*libbox.SystemProxyStatus, error) {
	return &libbox.SystemProxyStatus{}, nil
}
func (p *platform) SetSystemProxyEnabled(bool) error { return errors.ErrUnsupported }
func (p *platform) TriggerNativeCrash() error        { return errors.ErrUnsupported }
func (p *platform) WriteDebugMessage(string)         {}
func (p *platform) ConnectSSHAgent() (int32, error)  { return -1, errors.ErrUnsupported }
