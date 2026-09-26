package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"golang.org/x/sys/unix"
)

const (
	xrayProxyTag    = "proxy"
	xrayTunTag      = "tun"
	xrayFragmentTag = "fragment"
)

type xrayEngine struct {
	instance *core.Instance
	tunFd    int
	logPath  string
}

// buildXrayConfig собирает полный конфиг xray-core: TUN-вход по fd от
// VpnService, выбранный сервер и правила обхода.
func buildXrayConfig(node *Node, options *Options, logPath string) (map[string]any, error) {
	config, err := buildXrayOutboundConfig(node, options, logPath)
	if err != nil {
		return nil, err
	}
	config["inbounds"] = []any{map[string]any{
		"tag":      xrayTunTag,
		"port":     0,
		"protocol": "tun",
		"settings": map[string]any{"name": "xray0", "MTU": options.MTU},
		"sniffing": map[string]any{
			"enabled":      true,
			"destOverride": []string{"http", "tls", "quic"},
			"routeOnly":    true,
		},
	}}
	return config, nil
}

// buildXrayOutboundConfig — конфиг без входов: сервер, обход блокировок и
// правила. Годится и для отдельной проверки сервера, где TUN не нужен.
func buildXrayOutboundConfig(node *Node, options *Options, logPath string) (map[string]any, error) {
	if node.Xray == nil {
		return nil, fmt.Errorf("сервер «%s» (%s) не поддерживается ядром Xray", node.Name, node.Type)
	}
	proxy := deepCopy(node.Xray).(map[string]any)
	proxy["tag"] = xrayProxyTag
	outbounds := []any{proxy}
	if fragment := xrayFragmentOutbound(proxy, options); fragment != nil {
		outbounds = append(outbounds, fragment)
	}
	if options.Mux.Enabled && muxAllowed(proxy) {
		proxy["mux"] = map[string]any{"enabled": true, "concurrency": options.Mux.Concurrency}
	}
	outbounds = append(outbounds,
		map[string]any{"tag": "direct", "protocol": "freedom"},
		map[string]any{"tag": "block", "protocol": "blackhole"},
	)

	var rules []any
	if options.BypassLAN {
		rules = append(rules, map[string]any{"type": "field", "ip": privateCIDRs, "outboundTag": "direct"})
	}
	if suffixes := directSuffixes(options); len(suffixes) > 0 {
		domains := make([]string, 0, len(suffixes))
		for _, suffix := range suffixes {
			domains = append(domains, "domain:"+suffix)
		}
		rules = append(rules, map[string]any{"type": "field", "domain": domains, "outboundTag": "direct"})
	}
	if !options.IPv6 {
		rules = append(rules, map[string]any{"type": "field", "ip": []string{"::/0"}, "outboundTag": "block"})
	}
	rules = append(rules, map[string]any{"type": "field", "network": "tcp,udp", "outboundTag": xrayProxyTag})

	logConfig := map[string]any{"loglevel": options.LogLevel, "access": "none"}
	if logPath != "" {
		logConfig["error"] = logPath
	}

	return map[string]any{
		"log":       logConfig,
		"dns":       map[string]any{"servers": []any{dnsHost(options.DNS)}},
		"outbounds": outbounds,
		"routing":   map[string]any{"domainStrategy": "AsIs", "rules": rules},
	}, nil
}

// xrayFragmentOutbound — freedom с фрагментацией и шумом, через который сервер
// подключается к прокси (sockopt.dialerProxy), как это делает Happ.
func xrayFragmentOutbound(proxy map[string]any, options *Options) map[string]any {
	if !options.Fragment.Enabled && !options.Noise.Enabled {
		return nil
	}
	stream := mapOf(proxy["streamSettings"])
	if stream == nil {
		stream = map[string]any{}
		proxy["streamSettings"] = stream
	}
	// Hysteria идёт поверх QUIC: TCP-фрагментация к нему неприменима.
	if network := stringOf(stream["network"]); network == "hysteria" || network == "kcp" || network == "mkcp" {
		return nil
	}
	settings := map[string]any{}
	if options.Fragment.Enabled {
		settings["fragment"] = map[string]any{
			"packets":  options.Fragment.Packets,
			"length":   options.Fragment.Length,
			"interval": options.Fragment.Interval,
		}
	}
	if options.Noise.Enabled {
		settings["noises"] = []any{map[string]any{
			"type":   options.Noise.Type,
			"packet": options.Noise.Packet,
			"delay":  options.Noise.Delay,
		}}
	}
	sockopt := mapOf(stream["sockopt"])
	if sockopt == nil {
		sockopt = map[string]any{}
		stream["sockopt"] = sockopt
	}
	sockopt["dialerProxy"] = xrayFragmentTag
	return map[string]any{
		"tag":            xrayFragmentTag,
		"protocol":       "freedom",
		"settings":       settings,
		"streamSettings": map[string]any{"sockopt": map[string]any{"tcpNoDelay": true}},
	}
}

// muxAllowed: XTLS Vision и Hysteria с mux несовместимы.
func muxAllowed(proxy map[string]any) bool {
	if stringOf(proxy["protocol"]) == "hysteria" {
		return false
	}
	flow := stringOf(xrayUser(mapOf(proxy["settings"]))["flow"])
	return !strings.HasPrefix(flow, "xtls-rprx-vision")
}

func startXray(node *Node, options *Options, fd int) (*xrayEngine, error) {
	logPath := ""
	if homeDir != "" {
		logPath = filepath.Join(homeDir, "xray.log")
		_ = os.WriteFile(logPath, nil, 0o600)
	}
	config, err := buildXrayConfig(node, options, logPath)
	if err != nil {
		return nil, err
	}
	payload, err := json.Marshal(config)
	if err != nil {
		return nil, err
	}

	// xray-core закрывать fd не умеет, поэтому отдаём ему копию и закрываем
	// её сами после остановки; оригинал остаётся за VpnService.
	tunFd, err := unix.Dup(fd)
	if err != nil {
		return nil, fmt.Errorf("не удалось скопировать fd туннеля: %w", err)
	}
	if err := os.Setenv(platform.TunFdKey, strconv.Itoa(tunFd)); err != nil {
		_ = unix.Close(tunFd)
		return nil, err
	}

	instance, err := core.StartInstance("json", payload)
	if err != nil {
		_ = unix.Close(tunFd)
		return nil, fmt.Errorf("xray не запустился: %w", err)
	}
	return &xrayEngine{instance: instance, tunFd: tunFd, logPath: logPath}, nil
}

func (e *xrayEngine) Stop() error {
	err := e.instance.Close()
	if fenceErr := fenceTunFd(e.tunFd); fenceErr != nil && err == nil {
		err = fenceErr
	}
	return err
}

// fenceTunFd выводит из игры копию fd, которую держал xray-core.
//
// TUN-вход xray-core не умеет закрываться: после instance.Close() его gVisor
// продолжает ждать пакеты на этом номере fd. Если номер просто закрыть, его
// займёт fd следующего запуска, и старый стек начнёт воровать пакеты у нового
// ядра. Поэтому номер навсегда занимается /dev/null, открытым только на
// запись: старый стек просыпается на первом пакете, получает EBADF и
// завершается, а номер больше никому не достаётся (одна «утечка» fd на запуск).
func fenceTunFd(fd int) error {
	null, err := unix.Open("/dev/null", unix.O_WRONLY|unix.O_CLOEXEC, 0)
	if err != nil {
		return unix.Close(fd)
	}
	defer unix.Close(null)
	if err := unix.Dup3(null, fd, unix.O_CLOEXEC); err != nil && !errors.Is(err, unix.EBADF) {
		return err
	}
	return nil
}

func (e *xrayEngine) Logs() string {
	if e.logPath == "" {
		return ""
	}
	return tailFile(e.logPath, 64*1024)
}

func (e *xrayEngine) DialProxy(ctx context.Context, host string, port int) (net.Conn, error) {
	ctx = session.SetForcedOutboundTagToContext(ctx, xrayProxyTag)
	return core.Dial(ctx, e.instance, xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port)))
}
