package nativebridge

import "testing"

type testAndroid struct {
	allow                       bool
	interfaces                  string
	lookupDomain, lookupNetwork string
}

func (a *testAndroid) Protect(int32) bool            { return a.allow }
func (a *testAndroid) OpenTun() (int32, error)       { return -1, nil }
func (a *testAndroid) InterfacesJSON() string        { return a.interfaces }
func (a *testAndroid) DefaultInterfaceName() string  { return "wlan0" }
func (a *testAndroid) DefaultInterfaceIndex() int32  { return 4 }
func (a *testAndroid) DefaultInterfaceMetered() bool { return false }
func (a *testAndroid) Lookup(domain, network string) (string, error) {
	a.lookupDomain = domain
	a.lookupNetwork = network
	return "192.0.2.1", nil
}

func TestRefusesUnprotectedOutboundSocket(t *testing.T) {
	a := &testAndroid{}
	p := &platform{android: a}
	if err := p.AutoDetectInterfaceControl(42); err == nil {
		t.Fatal("unprotected outbound socket was accepted")
	}
	a.allow = true
	if err := p.AutoDetectInterfaceControl(42); err != nil {
		t.Fatal(err)
	}
}

func TestAndroidNetworkMetadata(t *testing.T) {
	a := &testAndroid{interfaces: `[{"index":4,"name":"wlan0","mtu":1500,"addresses":["192.0.2.5/24","fe80::1/64"],"dns":["192.0.2.53"],"flags":65,"type":0}]`}
	p := &platform{android: a}
	result, err := p.GetInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	if !result.HasNext() {
		t.Fatal("Android network was discarded")
	}
	network := result.Next()
	if network.Index != 4 || network.Name != "wlan0" || network.DNSServer.Next() != "192.0.2.53" {
		t.Fatalf("incorrect network: %+v", network)
	}
	if result.HasNext() {
		t.Fatal("unexpected extra network")
	}
	a.interfaces = `[{"addresses":["fe80::1%wlan0/64"]}]`
	if _, err = p.GetInterfaces(); err == nil {
		t.Fatal("invalid prefix must fail before libbox's MustParsePrefix")
	}
}
