package libcore

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// fakeEngine пускает «прокси-путь» прямо на локальный сервер.
type fakeEngine struct{ addr string }

func (f fakeEngine) Stop() error  { return nil }
func (f fakeEngine) Logs() string { return "" }
func (f fakeEngine) DialProxy(ctx context.Context, _ string, _ int) (net.Conn, error) {
	var dialer net.Dialer
	return dialer.DialContext(ctx, "tcp", f.addr)
}

func pathByID(t *testing.T, res analysis, id string) probePath {
	t.Helper()
	for _, p := range res.Paths {
		if p.ID == id {
			return p
		}
	}
	t.Fatalf("нет пути %s: %#v", id, res.Paths)
	return probePath{}
}

func TestParseProbeTarget(t *testing.T) {
	cases := []struct {
		in, host, path string
		port           int
	}{
		{"youtube.com", "youtube.com", "/", 443},
		{"speed.cloudflare.com/__down?bytes=200000", "speed.cloudflare.com", "/__down?bytes=200000", 443},
		{"https://Example.org:8443/a/b", "example.org", "/a/b", 8443},
		{"127.0.0.1:9443", "127.0.0.1", "/", 9443},
	}
	for _, c := range cases {
		host, port, path, err := parseProbeTarget(c.in)
		if err != nil || host != c.host || port != c.port || path != c.path {
			t.Errorf("%q → %q %d %q %v", c.in, host, port, path, err)
		}
	}
	for _, bad := range []string{"", "http://example.org", "localhost", "exa mple.org"} {
		if _, _, _, err := parseProbeTarget(bad); err == nil {
			t.Errorf("%q должен быть отвергнут", bad)
		}
	}
}

func TestDNSQueryRoundTrip(t *testing.T) {
	query := buildDNSQuery(0x1234, "example.org")
	// Ответ = запрос с флагами ответа и одной A-записью через ссылку на имя.
	response := append([]byte{}, query...)
	binary.BigEndian.PutUint16(response[2:], 0x8180)
	binary.BigEndian.PutUint16(response[6:], 1)
	response = append(response, 0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184, 216, 34)
	ips, rcode, err := parseDNSResponse(response)
	if err != nil || rcode != 0 || len(ips) != 1 || ips[0] != "93.184.216.34" {
		t.Fatalf("ips=%v rcode=%d err=%v", ips, rcode, err)
	}
	if !bogusAnswer([]string{"10.10.34.35"}) || bogusAnswer([]string{"93.184.216.34"}) {
		t.Fatal("bogusAnswer")
	}
}

func TestAnalyzeOpenTarget(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, strings.Repeat("x", 80<<10))
	}))
	defer server.Close()
	addr := server.Listener.Addr().String()

	res, err := analyze(addr, fakeEngine{addr: addr}, EngineXray)
	if err != nil {
		t.Fatal(err)
	}
	if res.Verdict != "open" {
		t.Fatalf("verdict=%s summary=%s paths=%+v", res.Verdict, res.Summary, res.Paths)
	}
	direct := pathByID(t, res, "direct")
	if direct.Verdict != "ok" {
		t.Fatalf("direct: %+v", direct)
	}
	for _, step := range direct.Steps {
		if step.ID == "bulk" && step.Status != "ok" {
			t.Fatalf("bulk: %+v", step)
		}
	}
	if proxy := pathByID(t, res, "proxy"); proxy.Verdict != "ok" || proxy.Title != "Через VPN (Xray)" {
		t.Fatalf("proxy: %+v", proxy)
	}
	if _, err := json.Marshal(res); err != nil {
		t.Fatal(err)
	}
}

func TestAnalyzeStubAndNoProxy(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Location", "http://warning.rt.ru/?id=1")
		w.WriteHeader(http.StatusFound)
	}))
	defer server.Close()

	res, err := analyze(server.Listener.Addr().String(), nil, "")
	if err != nil {
		t.Fatal(err)
	}
	direct := pathByID(t, res, "direct")
	if direct.Verdict != "stub" || res.Verdict != "blocked" || len(res.Paths) != 1 {
		t.Fatalf("verdict=%s direct=%+v", res.Verdict, direct)
	}
	if len(res.Hints) == 0 {
		t.Fatal("без VPN должна быть подсказка подключиться")
	}
}

// Сервер отдаёт ~20 КБ и замирает — так ТСПУ режет зарубежные хостинги.
func TestAnalyzeThrottle(t *testing.T) {
	if testing.Short() {
		t.Skip("ждёт таймаут чтения")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	cert := httptest.NewTLSServer(http.NotFoundHandler())
	certificate := cert.TLS.Certificates[0]
	cert.Close()
	done := make(chan struct{})
	defer close(done)
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				server := tls.Server(conn, &tls.Config{Certificates: []tls.Certificate{certificate}})
				buf := make([]byte, 4096)
				_, _ = server.Read(buf)
				_, _ = io.WriteString(server, "HTTP/1.1 200 OK\r\nContent-Length: 500000\r\n\r\n")
				_, _ = server.Write([]byte(strings.Repeat("y", 18<<10)))
				select {
				case <-done:
				case <-time.After(20 * time.Second):
				}
			}(conn)
		}
	}()

	res, err := analyze(listener.Addr().String(), nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if direct := pathByID(t, res, "direct"); direct.Verdict != "throttle" {
		t.Fatalf("direct: %+v", direct)
	}
}

func TestScanPresets(t *testing.T) {
	var presets []scanPreset
	if err := json.Unmarshal([]byte(ScanPresets()), &presets); err != nil || len(presets) < 3 {
		t.Fatalf("presets: %v %d", err, len(presets))
	}
	for _, preset := range presets {
		for _, target := range preset.Targets {
			if _, _, _, err := parseProbeTarget(target); err != nil {
				t.Errorf("%s: %v", target, err)
			}
		}
	}
}
