package libcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Проверка доступности ресурсов — порт анализатора блокировок HomeNet
// (x-happy-x/nginx-proxy-manager, backend/manager/analyzer.go) на телефон.
//
// Цель проверяется по этапам двумя путями:
//
//	direct — напрямую через оператора: приложение исключено из туннеля, поэтому
//	         его сокеты видит ТСПУ, как если бы VPN не было;
//	proxy  — через прокси запущенного ядра (xray или mihomo).
//
// Этапы: DNS → TCP → TLS (настоящий SNI, а при отказе — нейтральный SNI, чтобы
// отличить фильтр по имени от блокировки IP) → HTTP (заглушки провайдера) →
// объём (обрыв на 16–20 КБ, которым ТСПУ режет зарубежные хостинги).

const (
	neutralSNI  = "vk.com" // открыт везде; используется только против IP цели
	bulkEnough  = 64 << 10
	freezeLow   = 10 << 10
	freezeHigh  = 32 << 10
	probeUA     = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"
	dohEndpoint = "https://cloudflare-dns.com/dns-query"
)

type probeStep struct {
	ID     string  `json:"id"` // dns | tcp | tls | http | bulk
	Title  string  `json:"title"`
	Status string  `json:"status"`          // ok | fail | warn | skip
	Cause  string  `json:"cause,omitempty"` // sni | freeze
	MS     float64 `json:"ms,omitempty"`
	Detail string  `json:"detail,omitempty"`
}

type probePath struct {
	ID      string      `json:"id"` // direct | proxy
	Title   string      `json:"title"`
	Verdict string      `json:"verdict"` // ok | dns | ip | sni | freeze | tls | stub | throttle | http | error | skip
	Summary string      `json:"summary"`
	FailAt  string      `json:"failAt,omitempty"`
	Steps   []probeStep `json:"steps"`
}

type dnsAnswer struct {
	ID       string   `json:"id"`
	Resolver string   `json:"resolver"`
	Via      string   `json:"via"`
	IPs      []string `json:"ips"`
	Rcode    string   `json:"rcode,omitempty"`
	MS       float64  `json:"ms"`
	Error    string   `json:"error,omitempty"`
	Verdict  string   `json:"verdict"` // reference | ok | differs | empty | bogus | error
}

type analysis struct {
	Target  string      `json:"target"`
	Host    string      `json:"host"`
	Port    int         `json:"port"`
	Path    string      `json:"path"`
	At      int64       `json:"at"`
	MS      float64     `json:"ms"`
	IP      string      `json:"ip,omitempty"`
	Engine  string      `json:"engine,omitempty"`
	DNS     []dnsAnswer `json:"dns"`
	Paths   []probePath `json:"paths"`
	Verdict string      `json:"verdict"` // open | bypassed | proxy-broken | down | blocked
	Summary string      `json:"summary"`
	Hints   []string    `json:"hints"`
}

type scanPreset struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Targets     []string `json:"targets"`
}

var scanPresets = []scanPreset{
	{ID: "whitelist", Name: "Белые списки РФ", Description: "Должны открываться напрямую даже при отключениях мобильного интернета.",
		Targets: []string{"gosuslugi.ru", "yandex.ru", "vk.com", "mail.ru", "online.sberbank.ru", "ozon.ru", "wildberries.ru", "rutube.ru", "avito.ru", "2gis.ru"}},
	{ID: "blocked", Name: "Заблокированные", Description: "Типичные блокировки РКН и замедления.",
		Targets: []string{"youtube.com", "instagram.com", "facebook.com", "x.com", "discord.com", "linkedin.com", "rutracker.org", "telegram.org", "signal.org", "medium.com"}},
	{ID: "ai", Name: "ИИ", Description: "Сервисы, которые сами закрыты для России.",
		Targets: []string{"chatgpt.com", "api.openai.com", "claude.ai", "api.anthropic.com", "gemini.google.com", "copilot.microsoft.com"}},
	{ID: "hosting", Name: "Зарубежный хостинг", Description: "Проверка обрыва после 16–20 КБ, который ТСПУ применяет к хостингам.",
		Targets: []string{"speed.cloudflare.com/__down?bytes=200000", "hetzner.com", "digitalocean.com", "github.com", "ovhcloud.com", "vultr.com", "fly.io"}},
	{ID: "games", Name: "Игры", Description: "Лаунчеры и магазины.",
		Targets: []string{"store.steampowered.com", "epicgames.com", "battle.net", "ea.com", "riotgames.com"}},
}

// ScanPresets — готовые наборы целей (JSON-массив).
func ScanPresets() string {
	payload, _ := json.Marshal(scanPresets)
	return string(payload)
}

// Scan проверяет одну цель ("host", "host/path" или "https://host:port/path")
// и возвращает разбор в JSON. Путь через VPN проверяется, только пока ядро
// запущено. Вызов блокирующий: 5–30 секунд в зависимости от блокировок.
func Scan(target string) (string, error) {
	mu.Lock()
	current, engineName := active, activeEngine
	mu.Unlock()
	res, err := analyze(target, current, engineName)
	if err != nil {
		return "", err
	}
	payload, err := json.Marshal(res)
	if err != nil {
		return "", err
	}
	return string(payload), nil
}

var hostPattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$`)

func parseProbeTarget(raw string) (host string, port int, path string, err error) {
	raw = strings.TrimSpace(strings.ToLower(raw))
	if raw == "" {
		return "", 0, "", errors.New("пустая цель")
	}
	if !strings.Contains(raw, "://") {
		raw = "https://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" {
		return "", 0, "", fmt.Errorf("не похоже на домен или URL: %q", raw)
	}
	if u.Scheme != "https" {
		return "", 0, "", errors.New("проверяется только https")
	}
	host = strings.TrimSuffix(u.Hostname(), ".")
	if net.ParseIP(host) == nil && (!hostPattern.MatchString(host) || !strings.Contains(host, ".")) {
		return "", 0, "", fmt.Errorf("некорректное имя хоста %q", host)
	}
	port = 443
	if p := u.Port(); p != "" {
		port, _ = strconv.Atoi(p)
		if port <= 0 || port > 65535 {
			return "", 0, "", errors.New("некорректный порт")
		}
	}
	path = u.EscapedPath()
	if path == "" {
		path = "/"
	}
	if u.RawQuery != "" {
		path += "?" + u.RawQuery
	}
	return host, port, path, nil
}

func msSince(t time.Time) float64 {
	return float64(time.Since(t).Microseconds()/10) / 100
}

type proxyDialer func(ctx context.Context, host string, port int) (net.Conn, error)

func analyze(target string, current engine, engineName string) (analysis, error) {
	host, port, path, err := parseProbeTarget(target)
	if err != nil {
		return analysis{}, err
	}
	var viaProxy proxyDialer
	if current != nil {
		viaProxy = current.DialProxy
	}
	started := time.Now()
	res := analysis{
		Target: target, Host: host, Port: port, Path: path, At: started.Unix(), Engine: engineName,
		DNS: []dnsAnswer{}, Paths: []probePath{}, Hints: []string{},
	}
	res.DNS = resolveAll(host, viaProxy)
	ip := pickIP(res.DNS)
	if net.ParseIP(host) != nil {
		// IP-адрес в цели — это сам ответ, даже если он из частной сети.
		ip = host
	}
	res.IP = ip

	var wg sync.WaitGroup
	var direct, proxy probePath
	wg.Add(1)
	go func() {
		defer wg.Done()
		direct = probeDirect(host, port, path, ip, res.DNS)
	}()
	if viaProxy != nil {
		wg.Add(1)
		go func() {
			defer wg.Done()
			proxy = probeProxy(host, port, path, viaProxy, engineName)
		}()
	}
	wg.Wait()
	res.Paths = append(res.Paths, direct)
	if viaProxy != nil {
		res.Paths = append(res.Paths, proxy)
	}
	summarize(&res, direct, proxy, viaProxy != nil)
	res.MS = msSince(started)
	return res, nil
}

// ---------- DNS ----------

func resolveAll(host string, viaProxy proxyDialer) []dnsAnswer {
	if net.ParseIP(host) != nil {
		return []dnsAnswer{{ID: "literal", Resolver: "IP-адрес", Via: "без DNS", IPs: []string{host}, Verdict: "reference"}}
	}
	type job struct {
		id, name, via string
		fn            func() ([]string, string, error)
	}
	referenceVia := "напрямую, зашифровано — эталон"
	if viaProxy != nil {
		referenceVia = "через VPN, зашифровано — эталон"
	}
	jobs := []job{
		{"doh", "Cloudflare DoH", referenceVia, func() ([]string, string, error) { return dohResolve(host, viaProxy) }},
		{"yandex", "77.88.8.8", "UDP напрямую через оператора", func() ([]string, string, error) { return udpResolve("77.88.8.8:53", host) }},
		{"cloudflare", "1.1.1.1", "UDP напрямую через оператора", func() ([]string, string, error) { return udpResolve("1.1.1.1:53", host) }},
	}
	out := make([]dnsAnswer, len(jobs))
	var wg sync.WaitGroup
	for index, item := range jobs {
		wg.Add(1)
		go func(index int, item job) {
			defer wg.Done()
			started := time.Now()
			ips, rcode, err := item.fn()
			answer := dnsAnswer{ID: item.id, Resolver: item.name, Via: item.via, IPs: ips, Rcode: rcode, MS: msSince(started)}
			if answer.IPs == nil {
				answer.IPs = []string{}
			}
			if err != nil {
				answer.Error = err.Error()
			}
			out[index] = answer
		}(index, item)
	}
	wg.Wait()
	if viaProxy != nil && (out[0].Error != "" || len(out[0].IPs) == 0) {
		// Прокси может лежать — тогда эталон берём напрямую.
		started := time.Now()
		if ips, rcode, err := dohResolve(host, nil); err == nil && len(ips) > 0 {
			out[0] = dnsAnswer{ID: "doh", Resolver: "Cloudflare DoH", Via: "напрямую, зашифровано — эталон", IPs: ips, Rcode: rcode, MS: msSince(started)}
		}
	}
	reference := map[string]bool{}
	for _, ip := range out[0].IPs {
		reference[ip] = true
	}
	for index := range out {
		answer := &out[index]
		switch {
		case index == 0:
			answer.Verdict = "reference"
			if answer.Error != "" {
				answer.Verdict = "error"
			}
		case answer.Error != "":
			answer.Verdict = "error"
		case len(answer.IPs) == 0:
			answer.Verdict = "empty"
		case bogusAnswer(answer.IPs):
			answer.Verdict = "bogus"
		case len(reference) > 0 && !intersects(answer.IPs, reference):
			answer.Verdict = "differs"
		default:
			answer.Verdict = "ok"
		}
	}
	return out
}

func intersects(ips []string, set map[string]bool) bool {
	for _, ip := range ips {
		if set[ip] {
			return true
		}
	}
	return false
}

var bogusNets = func() []*net.IPNet {
	var out []*net.IPNet
	for _, cidr := range []string{"0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15"} {
		_, network, _ := net.ParseCIDR(cidr)
		out = append(out, network)
	}
	return out
}()

// bogusAnswer: публичное имя, указывающее в частную сеть, — заглушка провайдера.
func bogusAnswer(ips []string) bool {
	for _, raw := range ips {
		ip := net.ParseIP(raw)
		if ip == nil {
			continue
		}
		for _, network := range bogusNets {
			if network.Contains(ip) {
				return true
			}
		}
	}
	return false
}

func pickIP(answers []dnsAnswer) string {
	for _, want := range []string{"reference", "ok", "differs"} {
		for _, answer := range answers {
			if answer.Verdict != want {
				continue
			}
			for _, ip := range answer.IPs {
				if parsed := net.ParseIP(ip); parsed != nil && parsed.To4() != nil && !bogusAnswer([]string{ip}) {
					return ip
				}
			}
		}
	}
	return ""
}

var dnsRcodes = map[int]string{0: "NOERROR", 1: "FORMERR", 2: "SERVFAIL", 3: "NXDOMAIN", 4: "NOTIMP", 5: "REFUSED"}

func udpResolve(server, host string) ([]string, string, error) {
	conn, err := net.DialTimeout("udp", server, 3*time.Second)
	if err != nil {
		return nil, "", err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	id := uint16(rand.Intn(65535))
	if _, err := conn.Write(buildDNSQuery(id, host)); err != nil {
		return nil, "", err
	}
	buf := make([]byte, 1500)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			return nil, "", errors.New("нет ответа за 3 с")
		}
		if n >= 2 && binary.BigEndian.Uint16(buf) == id {
			ips, rcode, err := parseDNSResponse(buf[:n])
			return ips, dnsRcodes[rcode], err
		}
	}
}

func buildDNSQuery(id uint16, host string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.BigEndian, [6]uint16{id, 0x0100, 1, 0, 0, 0})
	for _, label := range strings.Split(strings.TrimSuffix(host, "."), ".") {
		b.WriteByte(byte(len(label)))
		b.WriteString(label)
	}
	b.WriteByte(0)
	_ = binary.Write(&b, binary.BigEndian, [2]uint16{1, 1}) // A, IN
	return b.Bytes()
}

func parseDNSResponse(msg []byte) ([]string, int, error) {
	if len(msg) < 12 {
		return nil, 0, errors.New("короткий DNS-ответ")
	}
	rcode := int(msg[3] & 0x0F)
	qd, an := int(binary.BigEndian.Uint16(msg[4:])), int(binary.BigEndian.Uint16(msg[6:]))
	off := 12
	skipName := func() error {
		for off < len(msg) {
			length := int(msg[off])
			switch {
			case length == 0:
				off++
				return nil
			case length&0xC0 == 0xC0:
				off += 2
				return nil
			default:
				off += length + 1
			}
		}
		return errors.New("битое имя в DNS-ответе")
	}
	for i := 0; i < qd; i++ {
		if err := skipName(); err != nil {
			return nil, rcode, err
		}
		off += 4
	}
	var ips []string
	for i := 0; i < an; i++ {
		if err := skipName(); err != nil {
			return ips, rcode, err
		}
		if off+10 > len(msg) {
			break
		}
		kind := binary.BigEndian.Uint16(msg[off:])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8:]))
		off += 10
		if off+rdlen > len(msg) {
			break
		}
		if kind == 1 && rdlen == 4 {
			ips = append(ips, net.IP(msg[off:off+4]).String())
		}
		off += rdlen
	}
	return ips, rcode, nil
}

func dohResolve(host string, viaProxy proxyDialer) ([]string, string, error) {
	transport := &http.Transport{TLSHandshakeTimeout: 5 * time.Second}
	if viaProxy != nil {
		transport.DialContext = func(ctx context.Context, _, addr string) (net.Conn, error) {
			h, p, _ := net.SplitHostPort(addr)
			port, _ := strconv.Atoi(p)
			return viaProxy(ctx, h, port)
		}
	} else {
		dialer := &net.Dialer{Timeout: 5 * time.Second}
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			// Мимо системного резолвера: 1.1.1.1 тоже обслуживает cloudflare-dns.com.
			_, p, _ := net.SplitHostPort(addr)
			return dialer.DialContext(ctx, network, net.JoinHostPort("1.1.1.1", p))
		}
		transport.TLSClientConfig = &tls.Config{ServerName: "cloudflare-dns.com"}
	}
	client := &http.Client{Timeout: 8 * time.Second, Transport: transport}
	defer transport.CloseIdleConnections()
	req, _ := http.NewRequest(http.MethodGet, dohEndpoint+"?type=A&name="+url.QueryEscape(host), nil)
	req.Header.Set("Accept", "application/dns-json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, "", shortNetErr(err)
	}
	defer resp.Body.Close()
	var body struct {
		Status int `json:"Status"`
		Answer []struct {
			Type int    `json:"type"`
			Data string `json:"data"`
		} `json:"Answer"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<16)).Decode(&body); err != nil {
		return nil, "", fmt.Errorf("DoH ответил %d", resp.StatusCode)
	}
	var ips []string
	for _, answer := range body.Answer {
		if answer.Type == 1 {
			ips = append(ips, answer.Data)
		}
	}
	return ips, dnsRcodes[body.Status], nil
}

// ---------- соединения ----------

type countingConn struct {
	net.Conn
	read int64
}

func (c *countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.read += int64(n)
	return n, err
}

func shortNetErr(err error) error {
	if err == nil {
		return errors.New("нет данных")
	}
	var netErr net.Error
	msg := err.Error()
	switch {
	case errors.As(err, &netErr) && netErr.Timeout():
		return errors.New("таймаут")
	case strings.Contains(msg, "connection reset"):
		return errors.New("соединение сброшено (RST)")
	case strings.Contains(msg, "connection refused"):
		return errors.New("соединение отклонено")
	case strings.Contains(msg, "no route to host"), strings.Contains(msg, "network is unreachable"):
		return errors.New("нет маршрута")
	case errors.Is(err, io.EOF), strings.Contains(msg, "EOF"):
		return errors.New("соединение закрыто без ответа")
	}
	if index := strings.LastIndex(msg, ": "); index > 0 && len(msg)-index < 80 {
		return errors.New(msg[index+2:])
	}
	return err
}

// tlsServerAnswered: alert от сервера — тоже доказательство, что он достижим.
func tlsServerAnswered(err error) bool {
	return err != nil && strings.Contains(err.Error(), "remote error")
}

func tlsStep(conn net.Conn, sni string, timeout time.Duration) (*tls.Conn, probeStep, error) {
	step := probeStep{ID: "tls", Title: "TLS"}
	started := time.Now()
	// Сертификат проверяется вручную ниже: нужно увидеть и подменённый.
	client := tls.Client(conn, &tls.Config{ServerName: sni, InsecureSkipVerify: true, NextProtos: []string{"http/1.1"}, MinVersion: tls.VersionTLS12}) //nolint:gosec
	_ = conn.SetDeadline(time.Now().Add(timeout))
	err := client.Handshake()
	step.MS = msSince(started)
	if err != nil {
		step.Status, step.Detail = "fail", "SNI "+sni+": "+shortNetErr(err).Error()
		return nil, step, err
	}
	state := client.ConnectionState()
	step.Status = "ok"
	step.Detail = map[uint16]string{tls.VersionTLS12: "TLS 1.2", tls.VersionTLS13: "TLS 1.3"}[state.Version]
	if len(state.PeerCertificates) > 0 {
		cert := state.PeerCertificates[0]
		issuer := cert.Issuer.CommonName
		if issuer == "" && len(cert.Issuer.Organization) > 0 {
			issuer = cert.Issuer.Organization[0]
		}
		if err := cert.VerifyHostname(sni); err != nil {
			step.Status = "warn"
			step.Detail += fmt.Sprintf(" · сертификат не для %s (выдан %s) — возможна заглушка или подмена", sni, issuer)
		} else {
			step.Detail += " · сертификат " + issuer
		}
	}
	return client, step, nil
}

var stubPattern = regexp.MustCompile(`(?i)warning\.rt\.ru|blocked|blocklist|zapret|rkn\.gov|eais|fz-?139|stop\.|lawfilter|block\.`)

// httpSteps отправляет GET и читает до bulkEnough байт: классифицирует ответ
// (заглушка) и передачу (обрыв на 16–20 КБ).
func httpSteps(client *tls.Conn, raw *countingConn, host, path string) []probeStep {
	httpStep := probeStep{ID: "http", Title: "HTTP"}
	bulk := probeStep{ID: "bulk", Title: "Объём 16–20 КБ"}
	started := time.Now()
	_ = client.SetDeadline(time.Now().Add(8 * time.Second))
	fmt.Fprintf(client, "GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nAccept: text/html,*/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n", path, host, probeUA)
	var head bytes.Buffer
	buf := make([]byte, 16<<10)
	headerDone, complete := false, false
	var plain, bodyStart, contentLength int64 = 0, 0, -1
	var readErr error
	deadline := time.Now().Add(15 * time.Second)
	for raw.read < bulkEnough && time.Now().Before(deadline) {
		_ = client.SetReadDeadline(time.Now().Add(6 * time.Second))
		n, err := client.Read(buf)
		plain += int64(n)
		if !headerDone && n > 0 {
			head.Write(buf[:n])
			if index := bytes.Index(head.Bytes(), []byte("\r\n\r\n")); index >= 0 || head.Len() > 64<<10 {
				headerDone = true
				httpStep.MS = msSince(started)
				bodyStart = int64(index + 4)
				contentLength = headerContentLength(head.String())
			}
		}
		if headerDone && contentLength >= 0 && plain-bodyStart >= contentLength {
			complete = true
			break
		}
		if err != nil {
			readErr = err
			break
		}
	}
	got := raw.read
	kb := fmt.Sprintf("%.1f КБ", float64(got)/1024)
	if head.Len() == 0 {
		httpStep.Status = "fail"
		httpStep.MS = msSince(started)
		httpStep.Detail = "нет ответа: " + shortNetErr(readErr).Error() + " (получено " + kb + " вместе с рукопожатием)"
		bulk.Status, bulk.Detail = "skip", "нет данных"
		if got >= freezeLow && got <= freezeHigh {
			bulk.Status, bulk.Detail = "fail", "соединение замерло на "+kb+" — похоже на ТСПУ"
		}
		return []probeStep{httpStep, bulk}
	}
	status, location := parseHTTPHead(head.String())
	httpStep.Status = "ok"
	httpStep.Detail = status
	if location != "" {
		httpStep.Detail += " → " + location
	}
	if strings.Contains(status, " 451") || (location != "" && stubPattern.MatchString(location)) {
		httpStep.Status = "fail"
		httpStep.Detail = "заглушка блокировки: " + httpStep.Detail
	}
	finished := complete || errors.Is(readErr, io.EOF) || (readErr != nil && strings.Contains(readErr.Error(), "close_notify"))
	switch {
	case got >= bulkEnough:
		bulk.Status, bulk.Detail = "ok", "получено "+kb+" без обрыва"
	case finished && got < 24<<10:
		bulk.Status, bulk.Detail = "skip", "ответ целиком "+kb+" — мало, чтобы проверить порог 16–20 КБ"
	case finished:
		bulk.Status, bulk.Detail = "ok", "ответ получен целиком, "+kb
	case got >= freezeLow && got <= freezeHigh:
		bulk.Status, bulk.Detail = "fail", "поток замер на "+kb+" ("+shortNetErr(readErr).Error()+") — типичный обрыв ТСПУ"
	default:
		bulk.Status, bulk.Detail = "warn", "поток прервался на "+kb+": "+shortNetErr(readErr).Error()
	}
	return []probeStep{httpStep, bulk}
}

func headerContentLength(head string) int64 {
	for _, line := range strings.Split(head, "\r\n")[1:] {
		if line == "" {
			break
		}
		if key, value, ok := strings.Cut(line, ":"); ok && strings.EqualFold(strings.TrimSpace(key), "content-length") {
			if n, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64); err == nil {
				return n
			}
		}
	}
	return -1
}

func parseHTTPHead(head string) (status, location string) {
	lines := strings.Split(head, "\r\n")
	if len(lines) > 0 {
		status = strings.TrimSpace(lines[0])
		if len(status) > 60 {
			status = status[:60]
		}
	}
	for _, line := range lines[1:] {
		if line == "" {
			break
		}
		if key, value, ok := strings.Cut(line, ":"); ok && strings.EqualFold(strings.TrimSpace(key), "location") {
			location = strings.TrimSpace(value)
			if len(location) > 120 {
				location = location[:120] + "…"
			}
		}
	}
	return
}

func dnsStepFrom(answers []dnsAnswer, id, title string) probeStep {
	for _, answer := range answers {
		if answer.ID != id && !(answer.ID == "literal") {
			continue
		}
		step := probeStep{ID: "dns", Title: title, MS: answer.MS}
		switch answer.Verdict {
		case "ok", "reference":
			step.Status, step.Detail = "ok", strings.Join(answer.IPs, ", ")
		case "differs":
			step.Status, step.Detail = "warn", strings.Join(answer.IPs, ", ")+" — не совпадает с эталоном (бывает у CDN)"
		case "bogus":
			step.Status, step.Detail = "fail", strings.Join(answer.IPs, ", ")+" — подмена: частный адрес вместо настоящего"
		case "empty":
			step.Status, step.Detail = "fail", "адрес не отдан ("+orDefault(answer.Rcode, "пусто")+")"
		default:
			step.Status, step.Detail = "fail", answer.Error
		}
		return step
	}
	return probeStep{ID: "dns", Title: title, Status: "skip"}
}

// ---------- пути ----------

func finishPath(p *probePath) {
	for _, step := range p.Steps {
		if step.Status != "fail" {
			continue
		}
		p.FailAt = step.ID
		switch step.ID {
		case "dns":
			p.Verdict, p.Summary = "dns", "DNS: "+step.Detail
		case "tcp":
			p.Verdict, p.Summary = "ip", "TCP не соединяется — блокировка по IP или сервер недоступен"
		case "tls":
			p.Verdict, p.Summary = "tls", "TLS не устанавливается: "+step.Detail
			switch step.Cause {
			case "sni":
				p.Verdict, p.Summary = "sni", "ТСПУ режет по имени сайта (SNI): с другим SNI тот же IP отвечает"
			case "freeze":
				p.Verdict, p.Summary = "freeze", "TCP соединяется, но дальше данные не идут ни с каким SNI — ТСПУ глушит этот IP"
			}
		case "http":
			p.Verdict, p.Summary = "http", step.Detail
			if strings.HasPrefix(step.Detail, "заглушка") {
				p.Verdict = "stub"
			}
		case "bulk":
			p.Verdict, p.Summary = "throttle", step.Detail
		default:
			p.Verdict, p.Summary = "error", step.Detail
		}
		return
	}
	p.Verdict, p.Summary = "ok", "работает"
	for _, step := range p.Steps {
		if step.Status == "warn" {
			p.Summary = "работает, есть замечания"
			break
		}
	}
}

func skipRest(p *probePath, ids ...string) {
	titles := map[string]string{"tcp": "TCP", "tls": "TLS", "http": "HTTP", "bulk": "Объём 16–20 КБ"}
	for _, id := range ids {
		p.Steps = append(p.Steps, probeStep{ID: id, Title: titles[id], Status: "skip"})
	}
}

func probeDirect(host string, port int, path, ip string, answers []dnsAnswer) probePath {
	p := probePath{ID: "direct", Title: "Напрямую через оператора"}
	p.Steps = append(p.Steps, dnsStepFrom(answers, "yandex", "DNS 77.88.8.8"))
	if ip == "" {
		p.Steps = append(p.Steps, probeStep{ID: "tcp", Title: "TCP", Status: "fail", Detail: "не удалось узнать IP ни одним резолвером"})
		skipRest(&p, "tls", "http", "bulk")
		finishPath(&p)
		return p
	}
	dialer := net.Dialer{Timeout: 6 * time.Second}
	addr := net.JoinHostPort(ip, strconv.Itoa(port))
	started := time.Now()
	conn, err := dialer.Dial("tcp", addr)
	tcp := probeStep{ID: "tcp", Title: "TCP", MS: msSince(started)}
	if err != nil {
		tcp.Status, tcp.Detail = "fail", addr+": "+shortNetErr(err).Error()
		p.Steps = append(p.Steps, tcp)
		skipRest(&p, "tls", "http", "bulk")
		finishPath(&p)
		return p
	}
	tcp.Status, tcp.Detail = "ok", addr
	p.Steps = append(p.Steps, tcp)
	raw := &countingConn{Conn: conn}
	defer conn.Close()
	client, step, err := tlsStep(raw, host, 8*time.Second)
	p.Steps = append(p.Steps, step)
	if err != nil {
		if !tlsServerAnswered(err) && net.ParseIP(host) == nil {
			// Тот же IP с нейтральным SNI: если так отвечает, фильтруют имя.
			last := &p.Steps[len(p.Steps)-1]
			if alt, altErr := dialer.Dial("tcp", addr); altErr == nil {
				_, altStep, altTLSErr := tlsStep(alt, neutralSNI, 6*time.Second)
				alt.Close()
				if altTLSErr == nil || tlsServerAnswered(altTLSErr) {
					last.Cause = "sni"
					last.Detail += " · с SNI " + neutralSNI + " тот же IP отвечает"
				} else {
					last.Detail += " · с SNI " + neutralSNI + " тоже нет ответа"
					if strings.Contains(altStep.Detail, "таймаут") && strings.Contains(step.Detail, "таймаут") {
						last.Cause = "freeze"
					}
				}
			}
		}
		skipRest(&p, "http", "bulk")
		finishPath(&p)
		return p
	}
	p.Steps = append(p.Steps, httpSteps(client, raw, host, path)...)
	finishPath(&p)
	return p
}

func probeProxy(host string, port int, path string, dial proxyDialer, engineName string) probePath {
	title := "Через VPN"
	switch engineName {
	case EngineXray:
		title = "Через VPN (Xray)"
	case EngineMihomo:
		title = "Через VPN (Mihomo)"
	}
	p := probePath{ID: "proxy", Title: title}
	p.Steps = append(p.Steps, probeStep{ID: "dns", Title: "DNS", Status: "skip", Detail: "имя резолвит прокси-сервер"})
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	started := time.Now()
	conn, err := dial(ctx, host, port)
	tcp := probeStep{ID: "tcp", Title: "Соединение через прокси", MS: msSince(started)}
	if err != nil {
		tcp.Status, tcp.Detail = "fail", shortNetErr(err).Error()
		p.Steps = append(p.Steps, tcp)
		skipRest(&p, "tls", "http", "bulk")
		finishPath(&p)
		return p
	}
	defer conn.Close()
	tcp.Status, tcp.Detail = "ok", net.JoinHostPort(host, strconv.Itoa(port))
	p.Steps = append(p.Steps, tcp)
	raw := &countingConn{Conn: conn}
	client, step, err := tlsStep(raw, host, 10*time.Second)
	p.Steps = append(p.Steps, step)
	if err != nil {
		skipRest(&p, "http", "bulk")
		finishPath(&p)
		return p
	}
	p.Steps = append(p.Steps, httpSteps(client, raw, host, path)...)
	finishPath(&p)
	return p
}

var verdictWords = map[string]string{
	"dns": "DNS", "ip": "блокировка по IP", "sni": "ТСПУ по SNI", "tls": "TLS не устанавливается",
	"stub": "заглушка", "throttle": "ТСПУ: обрыв 16–20 КБ", "freeze": "ТСПУ глушит IP", "http": "ошибка HTTP", "error": "ошибка",
}

func summarize(res *analysis, direct, proxy probePath, hasProxy bool) {
	directOK := direct.Verdict == "ok"
	proxyOK := hasProxy && proxy.Verdict == "ok"
	switch {
	case hasProxy && directOK && proxyOK:
		res.Verdict, res.Summary = "open", "Открыт и напрямую, и через VPN"
	case hasProxy && !directOK && proxyOK:
		res.Verdict, res.Summary = "bypassed", "Заблокирован у оператора ("+verdictWords[direct.Verdict]+"), через VPN работает"
	case hasProxy && directOK && !proxyOK:
		res.Verdict, res.Summary = "proxy-broken", "Напрямую работает, а через VPN — нет ("+proxy.Summary+")"
	case hasProxy:
		res.Verdict, res.Summary = "down", "Не работает ни напрямую ("+verdictWords[direct.Verdict]+"), ни через VPN"
	case directOK:
		res.Verdict, res.Summary = "open", "Открыт напрямую"
	default:
		res.Verdict, res.Summary = "blocked", "Напрямую не работает: "+direct.Summary
	}
	if !hasProxy && !directOK {
		res.Hints = append(res.Hints, "Подключите VPN и повторите проверку, чтобы узнать, помогает ли прокси.")
	}
	if hasProxy && directOK && proxyOK {
		res.Hints = append(res.Hints, "Сайт открыт напрямую — его можно не пускать через VPN.")
	}
	for _, answer := range res.DNS {
		switch {
		case answer.Verdict == "bogus":
			res.Hints = append(res.Hints, "Резолвер «"+answer.Resolver+"» подменяет адрес ("+strings.Join(answer.IPs, ", ")+") — блокировка на уровне DNS.")
		case answer.Verdict == "empty" && len(res.DNS[0].IPs) > 0:
			res.Hints = append(res.Hints, "Резолвер «"+answer.Resolver+"» не отдаёт адрес ("+orDefault(answer.Rcode, "пустой ответ")+"), хотя он существует — блокировка на уровне DNS.")
		}
	}
}
