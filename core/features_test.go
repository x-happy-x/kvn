package libcore

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/hub/executor"
	"github.com/xtls/xray-core/core"
	"gopkg.in/yaml.v3"
)

// Ссылки зашифрованы кодом sub-lab (app/happ-crypt5.js): с солью и без.
func TestDecryptHapp(t *testing.T) {
	raw, err := os.ReadFile("testdata/happ_links.json")
	if err != nil {
		t.Fatal(err)
	}
	var links struct{ Salted, Unsalted string }
	if err := json.Unmarshal(raw, &links); err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		links.Salted:   "https://sub.example.com/l/abc123?type=raw",
		links.Unsalted: "https://панель.рф/s/Токен",
	}
	for link, want := range cases {
		if !IsHappCrypt5(link) {
			t.Fatalf("не распознана как crypt5: %.40s", link)
		}
		got, err := DecryptHapp(link)
		if err != nil || got != want {
			t.Fatalf("DecryptHapp = %q, %v; want %q", got, err, want)
		}
	}
	if _, err := DecryptHapp("happ://crypt5/abcdefgh"); err == nil {
		t.Fatal("мусор должен давать ошибку")
	}
}

func TestXrayBypassOptions(t *testing.T) {
	nodes := parse(t, shareLinks)
	options, err := parseOptions(`{"fragment":{"enabled":true},"noise":{"enabled":true},"mux":{"enabled":true}}`)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"Нидерланды", "WS", "Trojan", "SS", "Hy2"} {
		node := byName(t, nodes, name)
		config, err := buildXrayConfig(node, options, "")
		if err != nil {
			t.Fatal(err)
		}
		payload, _ := json.Marshal(config)
		if _, err := core.LoadConfig("json", bytes.NewReader(payload)); err != nil {
			t.Fatalf("%s: xray-core отверг конфиг с фрагментацией: %v\n%s", name, err, payload)
		}
		proxy := config["outbounds"].([]any)[0].(map[string]any)
		dialer := stringOf(mapOf(mapOf(proxy["streamSettings"])["sockopt"])["dialerProxy"])
		switch name {
		case "Hy2":
			if dialer != "" || proxy["mux"] != nil {
				t.Fatalf("Hysteria2 не должен получать фрагментацию и mux: %s", payload)
			}
		case "Нидерланды":
			if dialer != xrayFragmentTag || proxy["mux"] != nil {
				t.Fatalf("Vision: фрагментация есть, mux нет: %s", payload)
			}
		default:
			if dialer != xrayFragmentTag || proxy["mux"] == nil {
				t.Fatalf("%s: ждали фрагментацию и mux: %s", name, payload)
			}
		}
	}
}

func TestOlcRTCFromForkIsMihomoOnly(t *testing.T) {
	body := `
proxies:
  - name: "OLCRTC"
    type: olcrtc
    auth-provider: jitsi
    transport: datachannel
    room-id: test-room
    encryption-key: 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
    dns-server: "8.8.8.8:53"
`
	node := parse(t, body)[0]
	if node.Supports(EngineXray) || !node.Supports(EngineMihomo) {
		t.Fatalf("olcrtc должен работать только в mihomo: %#v", node)
	}
	options, _ := parseOptions("")
	config, err := buildMihomoConfig(node, options, 0)
	if err != nil {
		t.Fatal(err)
	}
	payload, _ := yaml.Marshal(config)
	if _, err := executor.ParseWithBytes(payload); err != nil {
		t.Fatalf("mihomo (форк) не принял olcrtc: %v", err)
	}
}

// Отдельная проверка сервера без TUN: рабочий сервер, «молчащий» (порт открыт,
// но это не прокси) и недоступный.
func TestTestNode(t *testing.T) {
	httpListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer httpListener.Close()
	go http.Serve(httpListener, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	vlessPort := freeTCPPort(t)
	upstream := fmt.Sprintf(`{
  "log": {"loglevel": "none"},
  "inbounds": [{"listen": "127.0.0.1", "port": %d, "protocol": "vless",
    "settings": {"clients": [{"id": "2b1a5c6e-1111-4222-8333-944455556666"}], "decryption": "none"}}],
  "outbounds": [{"protocol": "freedom", "settings": {"redirect": %q}}]
}`, vlessPort, httpListener.Addr().String())
	instance, err := core.StartInstance("json", []byte(upstream))
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()

	silent, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer silent.Close()
	go func() {
		for {
			conn, err := silent.Accept()
			if err != nil {
				return
			}
			go func() { _, _ = io.Copy(io.Discard, conn) }()
		}
	}()
	closedPort := freeTCPPort(t)

	link := func(port int, name string) string {
		return fmt.Sprintf("vless://2b1a5c6e-1111-4222-8333-944455556666@127.0.0.1:%d?type=tcp&security=none&encryption=none#%s", port, name)
	}
	nodes := parse(t, strings.Join([]string{
		link(vlessPort, "ok"),
		link(silent.Addr().(*net.TCPAddr).Port, "silent"),
		link(closedPort, "down"),
	}, "\n"))
	want := map[string]string{"ok": "ok", "silent": "silent", "down": "down"}
	for _, engineName := range []string{EngineXray, EngineMihomo} {
		for _, node := range nodes {
			payload, err := TestNode(engineName, nodeJSON(t, node), "", "http://kvn.test/generate_204", 3000)
			if err != nil {
				t.Fatal(err)
			}
			var result nodeTest
			if err := json.Unmarshal([]byte(payload), &result); err != nil {
				t.Fatal(err)
			}
			if result.Verdict != want[node.Name] {
				t.Fatalf("%s/%s: %+v", engineName, node.Name, result)
			}
			if node.Name == "ok" && (result.Status != http.StatusNoContent || result.TCPMS <= 0) {
				t.Fatalf("%s/ok: %+v", engineName, result)
			}
		}
	}
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

func TestHTTPFetchAndCheckConnection(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Subscription-Userinfo", "upload=1; download=2")
		_, _ = io.WriteString(w, r.Method+" "+r.Header.Get("User-Agent"))
	}))
	defer server.Close()
	payload, err := HTTPFetch("POST", server.URL+"/x", `{"User-Agent":"Happ/3.10.0"}`, "{}", 5000)
	if err != nil {
		t.Fatal(err)
	}
	var result fetchResult
	if err := json.Unmarshal([]byte(payload), &result); err != nil {
		t.Fatal(err)
	}
	if result.Status != 200 || result.Body != "POST Happ/3.10.0" || result.Headers["subscription-userinfo"] == "" {
		t.Fatalf("%+v", result)
	}
	if _, err := HTTPFetch("GET", "http://kvn-nonexistent.invalid/", "", "", 3000); err == nil || !strings.Contains(err.Error(), "kvn-nonexistent.invalid") {
		t.Fatalf("ждали понятную ошибку DNS с именем хоста, получили %v", err)
	}
	if _, err := CheckConnection("", 1000); err == nil {
		t.Fatal("без запущенного ядра проверка должна падать")
	}
}

func TestDirectSuffixes(t *testing.T) {
	options, err := parseOptions(`{"directDomains":["+.wb.ru","  - +.vk.com","*.ya.ru","https://gosuslugi.ru/path","DOMAIN-SUFFIX,mos.ru","+.избирком.рф","wb.ru","","# comment"]}`)
	if err != nil {
		t.Fatal(err)
	}
	got := strings.Join(directSuffixes(options), " ")
	want := "wb.ru vk.com ya.ru gosuslugi.ru mos.ru xn--90alcckmno.xn--p1ai"
	if got != want {
		t.Fatalf("directSuffixes = %q, want %q", got, want)
	}
	options.DirectRU = true
	if suffixes := directSuffixes(options); suffixes[0] != "ru" || len(suffixes) != 9 {
		t.Fatalf("с DirectRU: %v", suffixes)
	}
}

// Ссылка сервера должна разбираться обратно в тот же сервер.
func TestShareLinkRoundTrip(t *testing.T) {
	nodes := parse(t, shareLinks)
	for _, node := range nodes {
		payload, _ := json.Marshal(node)
		link, err := ShareLink(string(payload))
		if err != nil {
			t.Logf("%s: %v", node.Name, err)
			continue
		}
		back := parse(t, link)
		if len(back) != 1 || back[0].Server != node.Server || back[0].Port != node.Port || back[0].Type != node.Type || back[0].Name != node.Name {
			t.Fatalf("%s: %s → %+v", node.Name, link, back)
		}
		if (back[0].Xray != nil) != (node.Xray != nil) {
			t.Fatalf("%s: поддержка xray потерялась: %s", node.Name, link)
		}
	}
}
