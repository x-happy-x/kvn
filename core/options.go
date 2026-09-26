package libcore

import (
	"encoding/json"
	"fmt"
	"strings"

	"golang.org/x/net/idna"
)

// Options — настройки подключения, общие для обоих ядер.
type Options struct {
	// DNS — сервер для запросов внутри туннеля и для резолва адресов серверов.
	DNS string `json:"dns"`
	// MTU интерфейса TUN; должно совпадать с тем, что выставил VpnService.
	MTU int `json:"mtu"`
	// BypassLAN пускает локальные сети мимо прокси.
	BypassLAN bool `json:"bypassLan"`
	// DirectRU пускает домены .ru/.рф/.su мимо прокси.
	DirectRU bool `json:"directRu"`
	// DirectDomains — сайты из «белых списков», которые идут мимо прокси
	// вместе с поддоменами: «wb.ru», «+.wb.ru», «избирком.рф».
	DirectDomains []string `json:"directDomains"`
	// IPv6 включает IPv6 внутри туннеля.
	IPv6 bool `json:"ipv6"`
	// LogLevel: debug, info, warning, error.
	LogLevel string `json:"logLevel"`

	// Опции обхода блокировок, как в Happ. Работают только в Xray: в mihomo
	// аналогов нет.
	Fragment FragmentOptions `json:"fragment"`
	Noise    NoiseOptions    `json:"noise"`
	Mux      MuxOptions      `json:"mux"`
}

// FragmentOptions — фрагментация TLS ClientHello (freedom.fragment в xray-core):
// ТСПУ не собирает SNI из нескольких сегментов.
type FragmentOptions struct {
	Enabled  bool   `json:"enabled"`
	Packets  string `json:"packets"`  // tlshello или диапазон пакетов, например 1-3
	Length   string `json:"length"`   // размер кусков в байтах, например 100-200
	Interval string `json:"interval"` // пауза между кусками в мс, например 10-20
}

// NoiseOptions — «шум» перед UDP-соединениями (freedom.noises): мешает
// распознать QUIC и прочий UDP по первым пакетам.
type NoiseOptions struct {
	Enabled bool   `json:"enabled"`
	Type    string `json:"type"`   // rand, str или base64
	Packet  string `json:"packet"` // для rand — длина, например 10-20
	Delay   string `json:"delay"`  // пауза после шума в мс, например 10-16
}

// MuxOptions — мультиплексирование соединений (mux.cool).
type MuxOptions struct {
	Enabled     bool `json:"enabled"`
	Concurrency int  `json:"concurrency"`
}

var privateCIDRs = []string{
	"10.0.0.0/8",
	"100.64.0.0/10",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"224.0.0.0/4",
	"fc00::/7",
	"fe80::/10",
}

var ruSuffixes = []string{"ru", "xn--p1ai", "su"}

// directSuffixes — домены, которые идут напрямую вместе с поддоменами:
// зона .ru/.рф/.su при DirectRU и сайты из белого списка. Записи чистятся от
// префиксов Clash («+.», «*.», «DOMAIN-SUFFIX,»), схем и путей, кириллица
// переводится в punycode, дубли отбрасываются.
func directSuffixes(options *Options) []string {
	var out []string
	seen := map[string]bool{}
	add := func(domain string) {
		if domain != "" && !seen[domain] {
			seen[domain] = true
			out = append(out, domain)
		}
	}
	if options.DirectRU {
		for _, suffix := range ruSuffixes {
			add(suffix)
		}
	}
	for _, raw := range options.DirectDomains {
		add(normalizeDomain(raw))
	}
	return out
}

func normalizeDomain(raw string) string {
	domain := strings.TrimSpace(raw)
	domain = strings.TrimLeft(domain, "-• \t")
	domain = strings.Trim(domain, "\"'")
	if index := strings.Index(domain, "#"); index >= 0 {
		domain = domain[:index]
	}
	upper := strings.ToUpper(domain)
	for _, prefix := range []string{"DOMAIN-SUFFIX,", "DOMAIN,", "DOMAIN:", "FULL:"} {
		if strings.HasPrefix(upper, prefix) {
			domain = domain[len(prefix):]
			break
		}
	}
	if index := strings.Index(domain, "://"); index >= 0 {
		domain = domain[index+3:]
	}
	if index := strings.IndexAny(domain, "/?:,"); index >= 0 {
		domain = domain[:index]
	}
	domain = strings.TrimSpace(domain)
	domain = strings.TrimPrefix(domain, "+.")
	domain = strings.TrimPrefix(domain, "*.")
	domain = strings.Trim(strings.ToLower(domain), ".")
	if domain == "" || strings.ContainsAny(domain, " \t") {
		return ""
	}
	if ascii, err := idna.Lookup.ToASCII(domain); err == nil {
		domain = ascii
	}
	return domain
}

func parseOptions(optionsJSON string) (*Options, error) {
	options := &Options{}
	if strings.TrimSpace(optionsJSON) != "" {
		if err := json.Unmarshal([]byte(optionsJSON), options); err != nil {
			return nil, fmt.Errorf("некорректные настройки: %w", err)
		}
	}
	if options.DNS == "" {
		options.DNS = "1.1.1.1"
	}
	if options.MTU <= 0 {
		options.MTU = 1500
	}
	defaultString(&options.Fragment.Packets, "tlshello")
	defaultString(&options.Fragment.Length, "100-200")
	defaultString(&options.Fragment.Interval, "10-20")
	defaultString(&options.Noise.Type, "rand")
	defaultString(&options.Noise.Packet, "10-20")
	defaultString(&options.Noise.Delay, "10-16")
	if options.Mux.Concurrency <= 0 {
		options.Mux.Concurrency = 8
	}
	switch options.LogLevel {
	case "debug", "info", "warning", "error":
	default:
		options.LogLevel = "warning"
	}
	return options, nil
}

// dnsHost убирает схему и порт: ядрам и резолверу нужен голый адрес.
func dnsHost(dns string) string {
	host := dns
	if index := strings.Index(host, "://"); index >= 0 {
		host = host[index+3:]
	}
	host = strings.TrimSuffix(host, "/dns-query")
	if strings.HasPrefix(host, "[") {
		if end := strings.Index(host, "]"); end > 0 {
			return host[1:end]
		}
	}
	if strings.Count(host, ":") == 1 {
		host = host[:strings.Index(host, ":")]
	}
	return host
}

func defaultString(value *string, fallback string) {
	if strings.TrimSpace(*value) == "" {
		*value = fallback
	}
}
