package libcore

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// ShareLink собирает ссылку сервера (vless://, vmess://, trojan://, ss://,
// hysteria2://, tuic://) — её показывают QR-кодом, и её понимают Happ,
// v2rayNG, Hiddify и сам KVN. Источник — прокси в формате mihomo; если его
// нет, он получается из outbound xray.
func ShareLink(nodeJSON string) (string, error) {
	node := &Node{}
	if err := json.Unmarshal([]byte(nodeJSON), node); err != nil {
		return "", fmt.Errorf("некорректный сервер: %w", err)
	}
	proxy := node.Clash
	if proxy == nil && node.Xray != nil {
		converted, err := xrayToClash(node.Xray)
		if err != nil {
			return "", err
		}
		proxy = converted
	}
	if proxy == nil {
		return "", fmt.Errorf("у сервера «%s» нет данных для ссылки", node.Name)
	}
	return shareLinkFromClash(node.Name, proxy)
}

func shareLinkFromClash(name string, proxy map[string]any) (string, error) {
	kind := strings.ToLower(stringOf(proxy["type"]))
	server := stringOf(proxy["server"])
	port := intOf(proxy["port"])
	if server == "" || port == 0 {
		return "", fmt.Errorf("у сервера нет адреса или порта")
	}
	host := net.JoinHostPort(server, strconv.Itoa(port))
	fragment := "#" + url.PathEscape(name)
	query := url.Values{}

	switch kind {
	case "vless", "trojan":
		secret := stringOf(proxy["uuid"])
		if kind == "trojan" {
			secret = stringOf(proxy["password"])
		}
		if kind == "vless" {
			query.Set("encryption", orDefault(stringOf(proxy["encryption"]), "none"))
			if flow := stringOf(proxy["flow"]); flow != "" {
				query.Set("flow", flow)
			}
		}
		shareTLS(proxy, query, kind == "trojan")
		shareTransport(proxy, query)
		return kind + "://" + url.PathEscape(secret) + "@" + host + "?" + query.Encode() + fragment, nil

	case "vmess":
		network := orDefault(stringOf(proxy["network"]), "tcp")
		entry := map[string]any{
			"v": "2", "ps": name, "add": server, "port": strconv.Itoa(port),
			"id": stringOf(proxy["uuid"]), "aid": strconv.Itoa(intOf(proxy["alterId"])),
			"scy": orDefault(stringOf(proxy["cipher"]), "auto"), "net": network, "type": "none",
		}
		values := url.Values{}
		shareTransport(proxy, values)
		entry["host"] = values.Get("host")
		entry["path"] = values.Get("path")
		if service := values.Get("serviceName"); service != "" {
			entry["path"] = service
		}
		if boolOf(proxy["tls"]) {
			entry["tls"] = "tls"
			entry["sni"] = proxyString(proxy, "servername", "sni")
			if fp := stringOf(proxy["client-fingerprint"]); fp != "" {
				entry["fp"] = fp
			}
		}
		payload, _ := json.Marshal(entry)
		return "vmess://" + base64.StdEncoding.EncodeToString(payload), nil

	case "ss", "shadowsocks":
		user := base64.RawURLEncoding.EncodeToString([]byte(stringOf(proxy["cipher"]) + ":" + stringOf(proxy["password"])))
		link := "ss://" + user + "@" + host
		if plugin := stringOf(proxy["plugin"]); plugin != "" {
			parts := []string{plugin}
			for key, value := range mapOf(proxy["plugin-opts"]) {
				parts = append(parts, key+"="+fmt.Sprint(value))
			}
			link += "?plugin=" + url.QueryEscape(strings.Join(parts, ";"))
		}
		return link + fragment, nil

	case "hysteria2", "hy2":
		if sni := proxyString(proxy, "sni", "servername"); sni != "" {
			query.Set("sni", sni)
		}
		if boolOf(proxy["skip-cert-verify"]) {
			query.Set("insecure", "1")
		}
		if obfs := stringOf(proxy["obfs"]); obfs != "" {
			query.Set("obfs", obfs)
			query.Set("obfs-password", stringOf(proxy["obfs-password"]))
		}
		return "hysteria2://" + url.PathEscape(stringOf(proxy["password"])) + "@" + host + "?" + query.Encode() + fragment, nil

	case "tuic":
		if sni := proxyString(proxy, "sni", "servername"); sni != "" {
			query.Set("sni", sni)
		}
		if cc := stringOf(proxy["congestion-controller"]); cc != "" {
			query.Set("congestion_control", cc)
		}
		if alpn := stringsOf(proxy["alpn"]); len(alpn) > 0 {
			query.Set("alpn", strings.Join(alpn, ","))
		}
		user := url.PathEscape(stringOf(proxy["uuid"])) + ":" + url.PathEscape(stringOf(proxy["password"]))
		return "tuic://" + user + "@" + host + "?" + query.Encode() + fragment, nil
	}
	return "", fmt.Errorf("для протокола %s ссылку собрать нельзя", kind)
}

func shareTLS(proxy map[string]any, query url.Values, defaultTLS bool) {
	reality := mapOf(proxy["reality-opts"])
	switch {
	case reality != nil:
		query.Set("security", "reality")
		query.Set("pbk", stringOf(reality["public-key"]))
		if sid := stringOf(reality["short-id"]); sid != "" {
			query.Set("sid", sid)
		}
	case boolOf(proxy["tls"]) || (defaultTLS && proxy["tls"] == nil):
		query.Set("security", "tls")
	default:
		query.Set("security", "none")
		return
	}
	if sni := proxyString(proxy, "servername", "sni"); sni != "" {
		query.Set("sni", sni)
	}
	if fp := stringOf(proxy["client-fingerprint"]); fp != "" {
		query.Set("fp", fp)
	}
	if alpn := stringsOf(proxy["alpn"]); len(alpn) > 0 {
		query.Set("alpn", strings.Join(alpn, ","))
	}
	if boolOf(proxy["skip-cert-verify"]) {
		query.Set("allowInsecure", "1")
	}
}

func shareTransport(proxy map[string]any, query url.Values) {
	network := orDefault(stringOf(proxy["network"]), "tcp")
	query.Set("type", network)
	switch network {
	case "ws":
		opts := mapOf(proxy["ws-opts"])
		query.Set("path", orDefault(stringOf(opts["path"]), "/"))
		if host := headerHost(mapOf(opts["headers"])); host != "" {
			query.Set("host", host)
		}
	case "grpc":
		query.Set("serviceName", stringOf(mapOf(proxy["grpc-opts"])["grpc-service-name"]))
		query.Set("mode", "gun")
	case "http", "h2":
		opts := mapOf(proxy[network+"-opts"])
		if paths := stringsOf(opts["path"]); len(paths) > 0 {
			query.Set("path", paths[0])
		}
		if hosts := stringsOf(opts["host"]); len(hosts) > 0 {
			query.Set("host", hosts[0])
		} else if host := headerHost(mapOf(opts["headers"])); host != "" {
			query.Set("host", host)
		}
		if network == "http" {
			query.Set("type", "tcp")
			query.Set("headerType", "http")
		}
	case "xhttp":
		opts := mapOf(proxy["xhttp-opts"])
		query.Set("path", orDefault(stringOf(opts["path"]), "/"))
		if host := stringOf(opts["host"]); host != "" {
			query.Set("host", host)
		}
		if mode := stringOf(opts["mode"]); mode != "" {
			query.Set("mode", mode)
		}
	}
}

func headerHost(headers map[string]any) string {
	for key, value := range headers {
		if strings.EqualFold(key, "host") {
			if list := stringsOf(value); len(list) > 0 {
				return list[0]
			}
			return stringOf(value)
		}
	}
	return ""
}

func proxyString(proxy map[string]any, keys ...string) string {
	for _, key := range keys {
		if value := stringOf(proxy[key]); value != "" {
			return value
		}
	}
	return ""
}
