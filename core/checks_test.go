package libcore

import (
	"bytes"

	"encoding/binary"
	"encoding/json"
	"github.com/xtls/xray-core/core"
	"io"
	"net"
	"strconv"
	"testing"
)

// dnsAnswerFor — ответ с одной A-записью на запрос query.
func dnsAnswerFor(query []byte, ip net.IP) []byte {
	msg := append([]byte{}, query...)
	msg[2] |= 0x80                         // QR
	binary.BigEndian.PutUint16(msg[6:], 1) // ANCOUNT
	msg = append(msg, 0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
	return append(msg, ip.To4()...)
}

func TestCheckDNSOverUDPAndTCP(t *testing.T) {
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			n, addr, err := udp.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = udp.WriteTo(dnsAnswerFor(buf[:n], net.IPv4(93, 184, 216, 34)), addr)
		}
	}()
	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	go func() {
		for {
			conn, err := tcp.Accept()
			if err != nil {
				return
			}
			var size [2]byte
			if _, err := io.ReadFull(conn, size[:]); err == nil {
				query := make([]byte, binary.BigEndian.Uint16(size[:]))
				if _, err := io.ReadFull(conn, query); err == nil {
					answer := dnsAnswerFor(query, net.IPv4(93, 184, 216, 35))
					frame := make([]byte, 2+len(answer))
					binary.BigEndian.PutUint16(frame, uint16(len(answer)))
					copy(frame[2:], answer)
					_, _ = conn.Write(frame)
				}
			}
			conn.Close()
		}
	}()

	for server, want := range map[string]string{
		udp.LocalAddr().String():       "93.184.216.34",
		"tcp://" + tcp.Addr().String(): "93.184.216.35",
	} {
		payload, err := CheckDNS(server, "example.com", false, 2000)
		if err != nil {
			t.Fatal(err)
		}
		var result dnsCheck
		_ = json.Unmarshal([]byte(payload), &result)
		if !result.OK || len(result.IPs) != 1 || result.IPs[0] != want {
			t.Fatalf("%s: %s", server, payload)
		}
	}

	if _, err := CheckDNS("1.1.1.1", "example.com", true, 1000); err == nil {
		t.Fatal("через VPN без запущенного ядра должна быть ошибка")
	}
}

func TestParseDNSServer(t *testing.T) {
	cases := map[string]string{
		"1.1.1.1":                      "udp 1.1.1.1 53",
		"tls://dns.google":             "tls dns.google 853",
		"https://1.1.1.1/dns-query":    "https 1.1.1.1 443",
		"tcp://[2606:4700::1111]:5353": "tcp 2606:4700::1111 5353",
	}
	for input, want := range cases {
		server, err := parseDNSServer(input)
		if err != nil {
			t.Fatalf("%s: %v", input, err)
		}
		if got := server.scheme + " " + server.host + " " + itoa(server.port); got != want {
			t.Fatalf("%s: %s, want %s", input, got, want)
		}
	}
	if _, err := parseDNSServer("quic://x"); err == nil {
		t.Fatal("неизвестная схема должна отвергаться")
	}
}

func TestPingTCPMethod(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	node, _ := json.Marshal(Node{Name: "local", Type: "vless", Server: "127.0.0.1", Port: port})
	if _, err := Ping(PingTCP, EngineXray, string(node), "", "", 1000); err != nil {
		t.Fatal(err)
	}
	if _, err := Ping("bogus", EngineXray, string(node), "", "", 1000); err == nil {
		t.Fatal("неизвестный способ должен отвергаться")
	}
}

func itoa(value int) string {
	return strconv.Itoa(value)
}

func TestXrayAcceptsDNSVariants(t *testing.T) {
	nodes := parse(t, shareLinks)
	for _, dns := range []string{"1.1.1.1", "https://1.1.1.1/dns-query", "tcp://8.8.8.8:53", "tls://1.1.1.1"} {
		config, err := BuildConfig(EngineXray, nodeJSON(t, nodes[0]), `{"dns":"`+dns+`"}`)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := core.LoadConfig("json", bytes.NewReader([]byte(config))); err != nil {
			t.Fatalf("%s: %v", dns, err)
		}
	}
}
