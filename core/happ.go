package libcore

import (
	"crypto/rsa"
	"crypto/x509"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

// Расшифровка ссылок happ://crypt5/... — порт sub-lab (app/happ-crypt5.js).
//
// Схема одного звена:
//
//	payload         = swapBlockHalves(marker[0..4] + body + marker[4..8])
//	body (с солью)  = nonce(12) + 2 произвольных байта + salt(8) + длина + packed
//	body (без соли) = nonce(12) + длина + packed
//	packed          = 1 произвольный байт + base64(chacha) + base64(RSA)
//	base64(RSA)     = RSA-PKCS1v15(swapPairs(base64(ключ XOR salt)))
//	base64(chacha)  = ChaCha20-Poly1305(nonce, swapPairs(base64(URL)))
//
// Таблица ключей по маркерам — та же, что у sub-lab (resources/happ/crypt5-keys.json).

//go:embed happ_crypt5_keys.json
var happKeysJSON []byte

var (
	happKeysOnce sync.Once
	happKeys     map[string]string
	happParsed   sync.Map // marker -> *rsa.PrivateKey
)

var happLinkPattern = regexp.MustCompile(`(?i)^(happ://)?crypt5/`)

// IsHappCrypt5 — похоже ли значение на ссылку happ://crypt5/...
func IsHappCrypt5(value string) bool {
	return happLinkPattern.MatchString(strings.TrimSpace(value))
}

// DecryptHapp расшифровывает happ://crypt5/... в обычную ссылку (обычно на подписку).
func DecryptHapp(link string) (string, error) {
	marker, body, err := parseHappLink(link)
	if err != nil {
		return "", err
	}
	key, err := happKey(marker)
	if err != nil {
		return "", err
	}
	preferSalted := len(body) > 12 && (body[12] < '0' || body[12] > '9')
	var firstErr error
	for _, salted := range []bool{preferSalted, !preferSalted} {
		url, err := decodeHappBody(body, key, salted)
		if err == nil && url == "" {
			err = errors.New("расшифровка дала пустую строку")
		}
		if err == nil && strings.ContainsFunc(url, func(r rune) bool { return r < 0x20 || r == 0x7f }) {
			err = errors.New("в расшифрованной строке управляющие символы")
		}
		if err == nil {
			return url, nil
		}
		if firstErr == nil {
			firstErr = err
		}
	}
	return "", firstErr
}

func happKey(marker string) (*rsa.PrivateKey, error) {
	if cached, ok := happParsed.Load(marker); ok {
		return cached.(*rsa.PrivateKey), nil
	}
	happKeysOnce.Do(func() {
		happKeys = map[string]string{}
		_ = json.Unmarshal(happKeysJSON, &happKeys)
	})
	encoded, ok := happKeys[marker]
	if !ok {
		return nil, fmt.Errorf("неизвестный маркер crypt5 «%s» — нужна свежая таблица ключей", marker)
	}
	der, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, fmt.Errorf("ключ crypt5 «%s» повреждён", marker)
	}
	parsed, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("ключ crypt5 «%s» повреждён: %w", marker, err)
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("ключ crypt5 «%s» не RSA", marker)
	}
	happParsed.Store(marker, key)
	return key, nil
}

func parseHappLink(link string) (string, []byte, error) {
	raw := strings.Trim(strings.TrimSpace(strings.TrimPrefix(link, "\ufeff")), `"'`)
	if len(raw) > 2<<20 {
		return "", nil, errors.New("ссылка длиннее 2 МиБ")
	}
	if !happLinkPattern.MatchString(raw) {
		return "", nil, errors.New("ожидалась ссылка happ://crypt5/... — другие форматы не поддерживаются")
	}
	rest := raw[strings.Index(strings.ToLower(raw), "crypt5/")+len("crypt5/"):]
	shuffled := swapBlockHalves([]byte(rest))
	if len(shuffled) < 8 {
		return "", nil, errors.New("тело crypt5 слишком короткое")
	}
	marker := string(shuffled[:4]) + string(shuffled[len(shuffled)-4:])
	return marker, shuffled[4 : len(shuffled)-4], nil
}

func decodeHappBody(body []byte, key *rsa.PrivateKey, salted bool) (string, error) {
	if len(body) < 13 {
		return "", errors.New("тело crypt5 слишком короткое")
	}
	nonce := body[:12]
	lengthStart := 12
	var salt []byte
	if salted {
		if len(body) < 22 {
			return "", errors.New("короткий заголовок соли")
		}
		salt = body[14:22]
		lengthStart = 22
	}
	lengthEnd := lengthStart
	for lengthEnd < len(body) && body[lengthEnd] >= '0' && body[lengthEnd] <= '9' {
		lengthEnd++
	}
	if lengthEnd == lengthStart {
		return "", errors.New("не указана длина сегмента")
	}
	segment, err := strconv.Atoi(string(body[lengthStart:lengthEnd]))
	if err != nil {
		return "", errors.New("длина сегмента не число")
	}
	packed := body[lengthEnd:]
	if len(packed) == 0 || segment > len(packed)-1 {
		return "", errors.New("сегмент обрезан")
	}
	encryptedURL := packed[1 : segment+1]
	rsaCiphertext, err := decodeLooseBase64(packed[segment+1:], "RSA-сегмент")
	if err != nil {
		return "", err
	}
	rsaPlaintext, err := rsa.DecryptPKCS1v15(nil, key, rsaCiphertext)
	if err != nil {
		return "", errors.New("RSA: ссылка повреждена или ключ не тот")
	}
	chachaKey, err := decodeLooseBase64(swapPairs(rsaPlaintext), "восстановленный ключ")
	if err != nil {
		return "", err
	}
	if len(chachaKey) != chacha20poly1305.KeySize {
		return "", fmt.Errorf("неожиданная длина симметричного ключа: %d", len(chachaKey))
	}
	for i := range chachaKey {
		if salt != nil {
			chachaKey[i] ^= salt[i%len(salt)]
		}
	}
	sealed, err := decodeLooseBase64(encryptedURL, "зашифрованный URL")
	if err != nil {
		return "", err
	}
	aead, err := chacha20poly1305.New(chachaKey)
	if err != nil {
		return "", err
	}
	intermediate, err := aead.Open(nil, nonce, sealed, nil)
	if err != nil {
		return "", errors.New("ChaCha20: ссылка повреждена")
	}
	url, err := decodeLooseBase64(swapPairs(intermediate), "расшифрованный URL")
	if err != nil {
		return "", err
	}
	return string(url), nil
}

// decodeLooseBase64 — base64 в том виде, в каком его шлёт Happ: с «-_», пробелами и без padding.
func decodeLooseBase64(input []byte, what string) ([]byte, error) {
	text := strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\n', '\r', '\t':
			return -1
		case '-':
			return '+'
		case '_':
			return '/'
		}
		return r
	}, string(input))
	text = strings.TrimRight(text, "=")
	out, err := base64.RawStdEncoding.DecodeString(text)
	if err != nil {
		return nil, fmt.Errorf("%s: строка не похожа на base64", what)
	}
	return out, nil
}

func swapPairs(input []byte) []byte {
	out := append([]byte{}, input...)
	for i := 0; i+1 < len(out); i += 2 {
		out[i], out[i+1] = out[i+1], out[i]
	}
	return out
}

func swapBlockHalves(input []byte) []byte {
	out := append([]byte{}, input...)
	for i := 0; i+3 < len(out); i += 4 {
		out[i], out[i+2] = out[i+2], out[i]
		out[i+1], out[i+3] = out[i+3], out[i+1]
	}
	return out
}
