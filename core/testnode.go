package libcore

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"time"

	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
)

// DefaultTestURL — адрес для проверки серверов: крошечный ответ 204, как у Happ.
const DefaultTestURL = "https://www.gstatic.com/generate_204"

// nodeTest — результат проверки сервера.
type nodeTest struct {
	// TCPMS — время TCP-рукопожатия с сервером; -1 — не соединяется, 0 — не мерили
	// (у сервера нет адреса, например olcrtc).
	TCPMS int `json:"tcpMs"`
	// OK — страница через прокси открылась.
	OK bool `json:"ok"`
	// MS — время до ответа через прокси (включая рукопожатие с сервером).
	MS     int    `json:"ms"`
	Status int    `json:"status,omitempty"`
	Error  string `json:"error,omitempty"`
	// Verdict: ok — работает; silent — пингуется, но ничего не открывает;
	// down — сервер недоступен; error — ядро не приняло сервер.
	Verdict string `json:"verdict"`
}

// TestNode проверяет сервер по-настоящему: поднимает отдельный экземпляр ядра
// без TUN, открывает testURL через прокси и замеряет время. Работающий VPN не
// трогает. Так находятся серверы, которые пингуются, но ничего не открывают.
func TestNode(engineName string, nodeJSON string, optionsJSON string, testURL string, timeoutMs int32) (string, error) {
	node, err := decodeNode(nodeJSON)
	if err != nil {
		return "", err
	}
	options, err := parseOptions(optionsJSON)
	if err != nil {
		return "", err
	}
	if testURL == "" {
		testURL = DefaultTestURL
	}
	if timeoutMs <= 0 {
		timeoutMs = 8000
	}
	result := testNode(engineName, node, options, testURL, time.Duration(timeoutMs)*time.Millisecond)
	payload, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(payload), nil
}

func testNode(engineName string, node *Node, options *Options, testURL string, timeout time.Duration) nodeTest {
	result := nodeTest{}
	if node.Server != "" && node.Port > 0 {
		result.TCPMS = int(TcpPing(node.Server, int32(node.Port), int32(minDuration(timeout, 3*time.Second)/time.Millisecond)))
	}

	dial, closer, err := standaloneDialer(engineName, node, options)
	if err != nil {
		result.Error = err.Error()
		result.Verdict = "error"
		return result
	}
	defer closer()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	status, elapsed, err := fetchThrough(ctx, dial, testURL)
	switch {
	case err == nil && status > 0 && status < 500:
		result.OK, result.Status, result.MS, result.Verdict = true, status, int(elapsed/time.Millisecond), "ok"
	case err == nil:
		result.Status, result.Verdict = status, "silent"
		result.Error = fmt.Sprintf("сайт ответил %d", status)
	default:
		result.Error = shortNetErr(err).Error()
		if result.TCPMS > 0 || result.TCPMS == 0 && node.Server == "" {
			result.Verdict = "silent"
		} else {
			result.Verdict = "down"
		}
	}
	return result
}

// standaloneDialer — dial через сервер отдельным экземпляром ядра.
func standaloneDialer(engineName string, node *Node, options *Options) (proxyDialer, func(), error) {
	useResolver(options.DNS)
	switch engineName {
	case EngineXray:
		config, err := buildXrayOutboundConfig(node, options, "")
		if err != nil {
			return nil, nil, err
		}
		config["log"] = map[string]any{"loglevel": "none", "access": "none"}
		payload, err := json.Marshal(config)
		if err != nil {
			return nil, nil, err
		}
		loaded, err := core.LoadConfig("json", bytes.NewReader(payload))
		if err != nil {
			return nil, nil, fmt.Errorf("xray не принял сервер: %w", err)
		}
		instance, err := core.New(loaded)
		if err != nil {
			return nil, nil, fmt.Errorf("xray не принял сервер: %w", err)
		}
		if err := instance.Start(); err != nil {
			_ = instance.Close()
			return nil, nil, fmt.Errorf("xray не запустился: %w", err)
		}
		dial := func(ctx context.Context, host string, port int) (net.Conn, error) {
			ctx = session.SetForcedOutboundTagToContext(ctx, xrayProxyTag)
			return core.Dial(ctx, instance, xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port)))
		}
		return dial, func() { _ = instance.Close() }, nil
	case EngineMihomo:
		if node.Clash == nil {
			return nil, nil, fmt.Errorf("сервер «%s» (%s) не поддерживается ядром Mihomo", node.Name, node.Type)
		}
		proxy, err := adapter.ParseProxy(deepCopy(node.Clash).(map[string]any))
		if err != nil {
			return nil, nil, fmt.Errorf("mihomo не принял сервер: %w", err)
		}
		dial := func(ctx context.Context, host string, port int) (net.Conn, error) {
			metadata := &C.Metadata{NetWork: C.TCP, Type: C.INNER, Host: host, DstPort: uint16(port)}
			if ip, err := netip.ParseAddr(host); err == nil {
				metadata.Host, metadata.DstIP = "", ip
			}
			return proxy.DialContext(ctx, metadata)
		}
		return dial, func() { _ = proxy.Close() }, nil
	}
	return nil, nil, fmt.Errorf("неизвестное ядро %q", engineName)
}

// fetchThrough открывает URL через dial и возвращает код ответа и время до заголовков.
func fetchThrough(ctx context.Context, dial proxyDialer, testURL string) (int, time.Duration, error) {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
			host, portText, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			port, _ := strconv.Atoi(portText)
			return dial(ctx, host, port)
		},
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: 8 * time.Second,
	}
	defer transport.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, testURL, nil)
	if err != nil {
		return 0, 0, err
	}
	req.Header.Set("User-Agent", probeUA)
	started := time.Now()
	resp, err := transport.RoundTrip(req)
	if err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return 0, 0, errors.New("таймаут: прокси не ответил")
		}
		return 0, 0, err
	}
	elapsed := time.Since(started)
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	resp.Body.Close()
	return resp.StatusCode, elapsed, nil
}

func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}
