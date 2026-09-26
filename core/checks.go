package libcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
)

// Способы пинга, как в Happ.
const (
	// PingTCP — время TCP-рукопожатия с сервером. Быстро, но не говорит,
	// пропускает ли сервер трафик.
	PingTCP = "tcp"
	// PingICMP — обычный ping (ICMP echo) до адреса сервера. Многие серверы
	// и сети его режут.
	PingICMP = "icmp"
	// PingProxyGet — GET тестового адреса через сам сервер (отдельный экземпляр
	// ядра): честная проверка, что сервер работает.
	PingProxyGet = "proxy-get"
	// PingProxyHead — то же с HEAD: меньше трафика.
	PingProxyHead = "proxy-head"
)

// Ping измеряет задержку до сервера выбранным способом и возвращает её в мс.
// Для proxy-get/proxy-head нужны ядро и настройки; testURL — адрес проверки.
func Ping(method string, engineName string, nodeJSON string, optionsJSON string, testURL string, timeoutMs int32) (int32, error) {
	node, err := decodeNode(nodeJSON)
	if err != nil {
		return 0, err
	}
	if timeoutMs <= 0 {
		timeoutMs = 3000
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	switch method {
	case "", PingTCP:
		if node.Server == "" || node.Port <= 0 {
			return 0, errors.New("у сервера нет адреса")
		}
		ms := TcpPing(node.Server, int32(node.Port), timeoutMs)
		if ms < 0 {
			return 0, errors.New("не соединяется")
		}
		return ms, nil
	case PingICMP:
		if node.Server == "" {
			return 0, errors.New("у сервера нет адреса")
		}
		elapsed, err := icmpPing(node.Server, timeout)
		if err != nil {
			return 0, err
		}
		return int32(elapsed / time.Millisecond), nil
	case PingProxyGet, PingProxyHead:
		options, err := parseOptions(optionsJSON)
		if err != nil {
			return 0, err
		}
		if testURL == "" {
			testURL = DefaultTestURL
		}
		dial, closer, err := standaloneDialer(engineName, node, options)
		if err != nil {
			return 0, err
		}
		defer closer()
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		httpMethod := http.MethodGet
		if method == PingProxyHead {
			httpMethod = http.MethodHead
		}
		status, elapsed, err := fetchThrough(ctx, dial, httpMethod, testURL)
		if err != nil {
			return 0, shortNetErr(err)
		}
		if status >= 500 {
			return 0, fmt.Errorf("сайт проверки ответил %d", status)
		}
		return int32(elapsed / time.Millisecond), nil
	}
	return 0, fmt.Errorf("неизвестный способ пинга %q", method)
}

// icmpPing шлёт один ICMP echo. На Android приложениям доступен
// непривилегированный ICMP-сокет (udp4); если его нет — пробуем raw.
func icmpPing(host string, timeout time.Duration) (time.Duration, error) {
	ips, _, err := resolveWithFallback(context.Background(), host)
	if err != nil {
		return 0, err
	}
	var target net.IP
	for _, raw := range ips {
		if ip := net.ParseIP(raw); ip != nil && ip.To4() != nil {
			target = ip
			break
		}
	}
	if target == nil {
		return 0, errors.New("нет IPv4-адреса для ICMP")
	}
	privileged := false
	conn, err := icmp.ListenPacket("udp4", "0.0.0.0")
	if err != nil {
		conn, err = icmp.ListenPacket("ip4:icmp", "0.0.0.0")
		if err != nil {
			return 0, fmt.Errorf("ICMP недоступен на этом устройстве: %w", err)
		}
		privileged = true
	}
	defer conn.Close()
	id := os.Getpid() & 0xffff
	seq := rand.Intn(0xffff)
	message := icmp.Message{
		Type: ipv4.ICMPTypeEcho,
		Body: &icmp.Echo{ID: id, Seq: seq, Data: []byte("kvn-ping")},
	}
	payload, err := message.Marshal(nil)
	if err != nil {
		return 0, err
	}
	var destination net.Addr = &net.UDPAddr{IP: target}
	if privileged {
		destination = &net.IPAddr{IP: target}
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	started := time.Now()
	if _, err := conn.WriteTo(payload, destination); err != nil {
		return 0, err
	}
	buf := make([]byte, 1500)
	for {
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			return 0, errors.New("нет ответа на ping")
		}
		reply, err := icmp.ParseMessage(1, buf[:n])
		if err != nil {
			continue
		}
		if reply.Type != ipv4.ICMPTypeEchoReply {
			continue
		}
		// Непривилегированный сокет сам подменяет ID, поэтому сверяем только seq.
		if echo, ok := reply.Body.(*icmp.Echo); ok && echo.Seq == seq {
			return time.Since(started), nil
		}
	}
}

// dnsCheck — результат проверки DNS-сервера.
type dnsCheck struct {
	OK    bool     `json:"ok"`
	MS    int      `json:"ms"`
	IPs   []string `json:"ips,omitempty"`
	Error string   `json:"error,omitempty"`
}

// CheckDNS спрашивает у DNS-сервера адрес domain и замеряет время ответа.
// server: «1.1.1.1», «udp://…», «tcp://…», «tls://…» (DoT) или
// «https://…/dns-query» (DoH). viaVPN — запрос идёт через работающий VPN
// (UDP тогда заменяется на TCP: прокси ядер пропускают поток). Ответ — JSON
// {ok, ms, ips, error}.
func CheckDNS(server string, domain string, viaVPN bool, timeoutMs int32) (string, error) {
	if strings.TrimSpace(domain) == "" {
		domain = "google.com"
	}
	if timeoutMs <= 0 {
		timeoutMs = 4000
	}
	var dial proxyDialer
	if viaVPN {
		mu.Lock()
		current := active
		mu.Unlock()
		if current == nil {
			return "", errors.New("VPN не подключён")
		}
		dial = current.DialProxy
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	started := time.Now()
	ips, err := queryDNS(ctx, server, strings.TrimSpace(domain), dial)
	result := dnsCheck{MS: int(time.Since(started) / time.Millisecond)}
	switch {
	case err != nil:
		result.MS = 0
		result.Error = shortNetErr(err).Error()
	case len(ips) == 0:
		result.MS = 0
		result.Error = "пустой ответ"
	case bogusAnswer(ips):
		result.IPs = ips
		result.MS = 0
		result.Error = "подменённый ответ (заглушка провайдера)"
	default:
		result.OK, result.IPs = true, ips
	}
	payload, _ := json.Marshal(result)
	return string(payload), nil
}

// dnsServer — разобранный адрес DNS-сервера.
type dnsServer struct {
	scheme string // udp, tcp, tls, https
	host   string
	port   int
	url    string
}

func parseDNSServer(raw string) (dnsServer, error) {
	text := strings.TrimSpace(raw)
	if text == "" {
		return dnsServer{}, errors.New("пустой адрес DNS")
	}
	if !strings.Contains(text, "://") {
		text = "udp://" + text
	}
	parsed, err := url.Parse(text)
	if err != nil || parsed.Hostname() == "" {
		return dnsServer{}, fmt.Errorf("некорректный адрес DNS %q", raw)
	}
	server := dnsServer{scheme: strings.ToLower(parsed.Scheme), host: parsed.Hostname(), url: text}
	defaults := map[string]int{"udp": 53, "tcp": 53, "tls": 853, "https": 443}
	port, ok := defaults[server.scheme]
	if !ok {
		return dnsServer{}, fmt.Errorf("неизвестная схема DNS %q", server.scheme)
	}
	if parsed.Port() != "" {
		port, _ = strconv.Atoi(parsed.Port())
	}
	server.port = port
	return server, nil
}

func queryDNS(ctx context.Context, raw string, domain string, dial proxyDialer) ([]string, error) {
	server, err := parseDNSServer(raw)
	if err != nil {
		return nil, err
	}
	id := uint16(rand.Intn(65535))
	query := buildDNSQuery(id, domain)
	address := net.JoinHostPort(server.host, strconv.Itoa(server.port))
	open := func() (net.Conn, error) {
		if dial != nil {
			return dial(ctx, server.host, server.port)
		}
		var dialer net.Dialer
		return dialer.DialContext(ctx, "tcp", address)
	}
	switch server.scheme {
	case "udp":
		if dial != nil {
			return tcpDNS(ctx, open, nil, query, id)
		}
		var dialer net.Dialer
		conn, err := dialer.DialContext(ctx, "udp", address)
		if err != nil {
			return nil, err
		}
		defer conn.Close()
		if deadline, ok := ctx.Deadline(); ok {
			_ = conn.SetDeadline(deadline)
		}
		if _, err := conn.Write(query); err != nil {
			return nil, err
		}
		buf := make([]byte, 1500)
		for {
			n, err := conn.Read(buf)
			if err != nil {
				return nil, errors.New("DNS не ответил")
			}
			if n >= 2 && binary.BigEndian.Uint16(buf) == id {
				ips, rcode, err := parseDNSResponse(buf[:n])
				return dnsResult(ips, rcode, err)
			}
		}
	case "tcp":
		return tcpDNS(ctx, open, nil, query, id)
	case "tls":
		return tcpDNS(ctx, open, &tls.Config{ServerName: server.host}, query, id)
	case "https":
		return dohWire(ctx, server, query, dial)
	}
	return nil, fmt.Errorf("неизвестная схема DNS %q", server.scheme)
}

// tcpDNS — DNS поверх TCP или TLS: сообщение с двухбайтовой длиной.
func tcpDNS(ctx context.Context, open func() (net.Conn, error), tlsConfig *tls.Config, query []byte, id uint16) ([]string, error) {
	conn, err := open()
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if tlsConfig != nil {
		client := tls.Client(conn, tlsConfig)
		if err := client.HandshakeContext(ctx); err != nil {
			return nil, err
		}
		conn = client
	}
	frame := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if _, err := conn.Write(frame); err != nil {
		return nil, err
	}
	var size [2]byte
	if _, err := io.ReadFull(conn, size[:]); err != nil {
		return nil, errors.New("DNS не ответил")
	}
	msg := make([]byte, binary.BigEndian.Uint16(size[:]))
	if _, err := io.ReadFull(conn, msg); err != nil {
		return nil, err
	}
	if len(msg) < 2 || binary.BigEndian.Uint16(msg) != id {
		return nil, errors.New("чужой DNS-ответ")
	}
	ips, rcode, err := parseDNSResponse(msg)
	return dnsResult(ips, rcode, err)
}

// dohWire — DoH по RFC 8484 (GET ?dns=…), так умеют все публичные DoH.
func dohWire(ctx context.Context, server dnsServer, query []byte, dial proxyDialer) ([]string, error) {
	transport := &http.Transport{DisableKeepAlives: true, TLSHandshakeTimeout: 5 * time.Second}
	if dial != nil {
		transport.DialContext = func(ctx context.Context, _, addr string) (net.Conn, error) {
			host, portText, _ := net.SplitHostPort(addr)
			port, _ := strconv.Atoi(portText)
			return dial(ctx, host, port)
		}
	} else if net.ParseIP(server.host) == nil {
		// Имя DoH-сервера ищем с запасными резолверами: системный DNS может врать.
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, portText, _ := net.SplitHostPort(addr)
			ips, _, err := resolveWithFallback(ctx, host)
			if err != nil {
				return nil, err
			}
			var dialer net.Dialer
			return dialer.DialContext(ctx, network, net.JoinHostPort(ips[0], portText))
		}
	}
	defer transport.CloseIdleConnections()
	parsed, _ := url.Parse(server.url)
	values := parsed.Query()
	// ID в DoH принято обнулять — так ответы кэшируются.
	wire := bytes.Clone(query)
	wire[0], wire[1] = 0, 0
	values.Set("dns", base64.RawURLEncoding.EncodeToString(wire))
	parsed.RawQuery = values.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, parsed.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-message")
	resp, err := transport.RoundTrip(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DoH ответил %d", resp.StatusCode)
	}
	msg, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return nil, err
	}
	ips, rcode, err := parseDNSResponse(msg)
	return dnsResult(ips, rcode, err)
}

func dnsResult(ips []string, rcode int, err error) ([]string, error) {
	if err != nil {
		return nil, err
	}
	if rcode != 0 {
		return nil, fmt.Errorf("DNS ответил %s", dnsRcodes[rcode])
	}
	return ips, nil
}
