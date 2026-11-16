package mobile

import (
	"github.com/artemk1337/vk-turn-proxy-desktop/pkg/proxy"
	"strings"
	"testing"
)

func TestNormalizeCookiesKeepsRequiredPair(t *testing.T) {
	got, err := NormalizeCookies("Cookie: ignored=private; p=login-value; remixsid=session-value; another=private")
	if err != nil {
		t.Fatal(err)
	}
	if got != "remixsid=session-value; p=login-value" {
		t.Fatal("unexpected cookie selection")
	}
}

func TestSavedAccountConfiguresActualTransport(t *testing.T) {
	t.Cleanup(func() {
		proxy.SetVKCookieAuth(false, "", nil)
		proxy.SetForceLegacyCaptcha(false)
		proxy.SetUplinkPace(0, 0)
	})
	for _, tc := range []struct {
		name            string
		required, saved bool
		wantPool        int32
		wantError       bool
	}{
		{"guest without account", false, false, 12, false},
		{"saved account default profile", false, true, 2, false},
		{"required account present", true, true, 2, false},
		{"required account missing", true, false, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := NewEngine()
			defer e.Stop()
			if tc.saved {
				if err := e.SetAccount("remixsid=session-value; p=login-value"); err != nil {
					t.Fatal(err)
				}
			}
			s, err := loadProfile(validProfile(t))
			if err != nil {
				t.Fatal(err)
			}
			s.Connections = 30
			s.VKAuth = tc.required
			p, err := e.begin(s, "")
			if tc.wantError {
				if err == nil {
					t.Fatal("required account was not enforced")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			// Cookie mode creates two relay slots per link. Anonymous mode uses
			// four credential sets for each ten connections.
			if got := p.GetStats().CredPoolSize; got != tc.wantPool {
				t.Fatalf("actual transport pool %d, want %d", got, tc.wantPool)
			}
		})
	}
}

func TestNormalizeCookiesNetscape(t *testing.T) {
	input := "# Netscape HTTP Cookie File\n#HttpOnly_.vk.ru\tTRUE\t/\tTRUE\t0\tremixsid\tsession-value\n.login.vk.ru\tTRUE\t/\tTRUE\t0\tp\tlogin-value\n"
	if _, err := NormalizeCookies(input); err != nil {
		t.Fatal(err)
	}
}

func TestInvalidCookiesDoNotExposeInput(t *testing.T) {
	for _, input := range []string{"secret-token", "remixsid=secret-token", "remixsid=secret-token\r\np=secret-token"} {
		_, err := NormalizeCookies(input)
		if err == nil || strings.Contains(err.Error(), "secret-token") {
			t.Fatal("invalid cookie error leaked input or was accepted")
		}
	}
}

func TestAccountRejectsExpiredCookies(t *testing.T) {
	input := ".vk.com\tTRUE\t/\tTRUE\t1\tremixsid\texpired-secret\n.login.vk.com\tTRUE\t/\tTRUE\t1\tp\texpired-secret\n"
	if _, err := NormalizeCookies(input); err == nil || strings.Contains(err.Error(), "expired-secret") {
		t.Fatal("expired cookies accepted or exposed")
	}
}

func TestSetAccountBeforeStart(t *testing.T) {
	e := NewEngine()
	if err := e.SetAccount("remixsid=session-value; p=login-value"); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(e.Status(), "session-value") {
		t.Fatal("status exposed cookies")
	}
	if err := e.SetAccount("invalid-secret"); err == nil {
		t.Fatal("invalid account accepted")
	}
	if e.cookieHeader != "remixsid=session-value; p=login-value" {
		t.Fatal("invalid import changed the active account")
	}
	e.Stop()
	if err := e.SetAccount(""); err == nil {
		t.Fatal("stopped engine accepted account mutation")
	}
}

func TestCaptchaURLValidation(t *testing.T) {
	for _, raw := range []string{"https://id.vk.ru/auth/captcha", "https://api.vk.com/captcha.php?sid=private"} {
		if !validCaptchaURL(raw) {
			t.Fatal("valid captcha URL rejected")
		}
	}
	for _, raw := range []string{"http://vk.com/captcha", "https://vk.com.evil.test/captcha", "https://evil.test@vk.com/captcha", "file:///private/file"} {
		if validCaptchaURL(raw) {
			t.Fatal("invalid captcha URL accepted")
		}
	}
	if NewEngine().Captcha() != `{"pending":false}` {
		t.Fatal("idle engine exposed a challenge")
	}
}
