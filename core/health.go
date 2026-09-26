package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// CheckConnection проверяет, что работающий VPN действительно пропускает
// трафик: открывает testURL через прокси запущенного ядра. Возвращает время
// ответа в мс. Нужна для периодической проверки и автопереключения сервера.
// method — GET или HEAD (пусто — GET).
func CheckConnection(method string, testURL string, timeoutMs int32) (int32, error) {
	mu.Lock()
	current := active
	mu.Unlock()
	if current == nil {
		return 0, errors.New("ядро не запущено")
	}
	if testURL == "" {
		testURL = DefaultTestURL
	}
	if timeoutMs <= 0 {
		timeoutMs = 8000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	status, elapsed, err := fetchThrough(ctx, current.DialProxy, strings.ToUpper(method), testURL)
	if err != nil {
		return 0, shortNetErr(err)
	}
	if status >= 500 {
		return 0, fmt.Errorf("сайт проверки ответил %d", status)
	}
	return int32(elapsed / time.Millisecond), nil
}

// fallbackResolvers — к ним обращаемся, если системный DNS не знает имя
// (бывает у провайдерского DNS и у приватного DNS Android).
var fallbackResolvers = []string{"77.88.8.8:53", "1.1.1.1:53", "8.8.8.8:53"}

type fetchResult struct {
	Status  int               `json:"status"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
	// Resolver — чем найден адрес: system, 77.88.8.8, doh…
	Resolver string `json:"resolver"`
}

// HTTPFetch делает HTTP-запрос напрямую (приложение вне туннеля) с запасным
// DNS: если системный резолвер не находит имя, адрес ищется через публичные
// DNS и Cloudflare DoH. headersJSON — объект «заголовок: значение».
// Ответ — JSON {status, headers, body, resolver}.
func HTTPFetch(method string, rawURL string, headersJSON string, body string, timeoutMs int32) (string, error) {
	if timeoutMs <= 0 {
		timeoutMs = 20000
	}
	headers := map[string]string{}
	if strings.TrimSpace(headersJSON) != "" {
		if err := json.Unmarshal([]byte(headersJSON), &headers); err != nil {
			return "", fmt.Errorf("некорректные заголовки: %w", err)
		}
	}
	resolver := "system"
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			ips, used, err := resolveWithFallback(ctx, host)
			if err != nil {
				return nil, err
			}
			resolver = used
			var lastErr error
			for _, ip := range ips {
				conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip, port))
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
		TLSHandshakeTimeout: 10 * time.Second,
		DisableKeepAlives:   true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: time.Duration(timeoutMs) * time.Millisecond}
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, rawURL, reader)
	if err != nil {
		return "", err
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	resp, err := client.Do(req)
	if err != nil {
		var notFound *hostNotFoundError
		if errors.As(err, &notFound) {
			return "", notFound
		}
		return "", shortNetErr(err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return "", shortNetErr(err)
	}
	result := fetchResult{Status: resp.StatusCode, Headers: map[string]string{}, Body: string(data), Resolver: resolver}
	for key, values := range resp.Header {
		if len(values) > 0 {
			result.Headers[strings.ToLower(key)] = values[0]
		}
	}
	payload, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(payload), nil
}

// resolveWithFallback: системный резолвер, затем публичные DNS по UDP, затем DoH.
func resolveWithFallback(ctx context.Context, host string) ([]string, string, error) {
	if net.ParseIP(host) != nil {
		return []string{host}, "literal", nil
	}
	system := &net.Resolver{PreferGo: false}
	lookupCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	ips, err := system.LookupHost(lookupCtx, host)
	cancel()
	if err == nil && len(ips) > 0 && !bogusAnswer(ips) {
		return ips, "system", nil
	}
	systemErr := err
	for _, server := range fallbackResolvers {
		found, _, udpErr := udpResolve(server, host)
		if udpErr == nil && len(found) > 0 && !bogusAnswer(found) {
			return found, strings.TrimSuffix(server, ":53"), nil
		}
	}
	if found, _, dohErr := dohResolve(host, nil); dohErr == nil && len(found) > 0 {
		return found, "doh", nil
	}
	if systemErr == nil {
		systemErr = errors.New("пустой ответ")
	}
	return nil, "", &hostNotFoundError{host: host, cause: systemErr}
}

// hostNotFoundError — имя не нашлось ни одним резолвером; текст содержит имя,
// чтобы было видно, какой адрес на самом деле запрашивался.
type hostNotFoundError struct {
	host  string
	cause error
}

func (e *hostNotFoundError) Error() string {
	return fmt.Sprintf("не удалось найти адрес «%s» ни системным DNS, ни через %s (%v) — проверьте адрес",
		e.host, strings.Join(trimPorts(fallbackResolvers), ", "), e.cause)
}

func trimPorts(servers []string) []string {
	out := make([]string, len(servers))
	for i, server := range servers {
		out[i] = strings.TrimSuffix(server, ":"+strconv.Itoa(53))
	}
	return out
}
