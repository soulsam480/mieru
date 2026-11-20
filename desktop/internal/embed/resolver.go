// Package embed turns a provider's embed page into a playlist URL.
//
// The Android app loads the page in a 2x2 WebView and watches its traffic. The
// desktop app does the same with a hidden mygo window: the preload script wraps
// fetch and XMLHttpRequest in every frame, records the first playlist request,
// and Go polls for it. Nothing is ever shown, and the window is destroyed as
// soon as it answers.
package embed

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/egoist/mygo"
)

const preload = `(function () {
  if (window.__mieruInstalled) return;
  window.__mieruInstalled = true;
  function report(url, headers) {
    try {
      parent.postMessage({ __mieru: { url: String(url), headers: headers || {}, page: location.href, cookie: document.cookie || "" } }, "*");
    } catch (e) {}
  }
  window.addEventListener("message", function (e) {
    var d = e.data;
    if (d && d.__mieru && !window.__mieru_resolved) {
      window.__mieru_resolved = JSON.stringify(d.__mieru);
    }
  });
  function isPlaylist(u) {
    u = String(u || "");
    return /\.m3u8(\?|$)/i.test(u) || /\/manifest/i.test(u) || /\.mp4(\?|$)/i.test(u);
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      try {
        var url = typeof input === "string" ? input : (input && input.url) || "";
        var h = {};
        if (init && init.headers) {
          new Headers(init.headers).forEach(function (v, k) { h[k] = v; });
        }
        if (isPlaylist(url)) report(new URL(url, location.href).href, h);
      } catch (e) {}
      return origFetch.apply(this, arguments);
    };
  }
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, url) {
    try { if (isPlaylist(url)) this.__mieruUrl = new URL(url, location.href).href; } catch (e) {}
    return origOpen.apply(this, arguments);
  };
  var origSet = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
    try { this.__mieruHeaders = this.__mieruHeaders || {}; this.__mieruHeaders[k] = v; } catch (e) {}
    return origSet.apply(this, arguments);
  };
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    if (this.__mieruUrl) { try { report(this.__mieruUrl, this.__mieruHeaders || {}); } catch (e) {} }
    return origSend.apply(this, arguments);
  };
  try {
    new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (e) { if (isPlaylist(e.name)) report(e.name, {}); });
    }).observe({ entryTypes: ["resource"] });
  } catch (e) {}
})();`

// Stream is what a provider page handed back.
type Stream struct {
	URL      string            `json:"url"`
	Headers  map[string]string `json:"headers"`
	Page     string            `json:"page"`
	Provider string            `json:"provider"`
}

type captured struct {
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
	Page    string            `json:"page"`
	Cookie  string            `json:"cookie"`
}

// Resolver serialises the hidden windows: each one is a full web view, and the
// mirrors are tried one at a time anyway.
type Resolver struct {
	mu sync.Mutex
}

// New returns a resolver.
func New() *Resolver { return &Resolver{} }

// Resolve loads the embed page and waits for its player to ask for a playlist.
func (r *Resolver) Resolve(ctx context.Context, embedURL, provider string) (*Stream, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	win := mygo.NewWindow(mygo.WindowOptions{
		Hidden: true,
		URL:    embedURL,
		Width:  2,
		Height: 2,
		Page: mygo.PageOptions{
			PreloadScript:        preload,
			PreloadAllFrames:     true,
			Autoplay:             mygo.AutoplayAllow,
			BackgroundThrottling: mygo.ThrottlingOff,
		},
	})
	defer win.Close()

	deadline := time.Now().Add(35 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(400 * time.Millisecond):
		}
		page := win.Page()
		if page == nil {
			return nil, fmt.Errorf("%s: window closed", provider)
		}
		value, err := page.Eval(`return window.__mieru_resolved || ""`)
		if err != nil {
			continue
		}
		text, ok := value.(string)
		if !ok || text == "" {
			continue
		}
		var payload captured
		if err := json.Unmarshal([]byte(text), &payload); err != nil || payload.URL == "" {
			continue
		}
		return toStream(payload, provider), nil
	}
	return nil, fmt.Errorf("%s did not return a playlist", provider)
}

func toStream(payload captured, provider string) *Stream {
	headers := map[string]string{}
	for key, value := range payload.Headers {
		if strings.EqualFold(key, "Cookie") || strings.EqualFold(key, "Referer") || strings.EqualFold(key, "Origin") {
			headers[key] = value
		}
	}
	if page, err := url.Parse(payload.Page); err == nil && page.Scheme != "" {
		if _, ok := headers["Referer"]; !ok {
			headers["Referer"] = payload.Page
		}
		if _, ok := headers["Origin"]; !ok {
			headers["Origin"] = page.Scheme + "://" + page.Host
		}
	}
	if payload.Cookie != "" {
		if _, ok := headers["Cookie"]; !ok {
			headers["Cookie"] = payload.Cookie
		}
	}
	return &Stream{
		URL:      payload.URL,
		Headers:  headers,
		Page:     payload.Page,
		Provider: provider,
	}
}
