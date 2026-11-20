// Package store keeps the app's three small JSON files: settings, progress and
// the watchlist. Each is loaded once, cached, and written atomically.
package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

func readJSON(path string, out any) {
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	_ = json.Unmarshal(data, out)
}

func writeJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	name := temp.Name()
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		os.Remove(name)
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		os.Remove(name)
		return err
	}
	if err := temp.Close(); err != nil {
		os.Remove(name)
		return err
	}
	if err := os.Rename(name, path); err != nil {
		os.Remove(name)
		return err
	}
	return nil
}

// Dir returns the app's data directory, creating nothing.
func Dir() string {
	if override := os.Getenv("MIERU_DATA_DIR"); override != "" {
		return override
	}
	base, err := os.UserConfigDir()
	if err != nil {
		base = os.TempDir()
	}
	return filepath.Join(base, "Mieru Desktop")
}

// ---------- settings ----------

// Source is one Miruro mirror the user can select.
type Source struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	BaseURL string `json:"baseUrl"`
}

// SubtitleStyle is how cues are drawn over the video.
type SubtitleStyle struct {
	Size       string `json:"size"`
	Color      string `json:"color"`
	Background string `json:"background"`
	Outline    bool   `json:"outline"`
}

// Settings is everything the settings page edits.
type Settings struct {
	Sources       []Source      `json:"sources"`
	SelectedID    string        `json:"selectedId"`
	ShowsSite     string        `json:"showsSite"`
	Track         string        `json:"track"`
	AutoSkip      bool          `json:"autoSkip"`
	SubtitleStyle SubtitleStyle `json:"subtitleStyle"`
}

// DefaultSettings mirrors the Android defaults.
func DefaultSettings() Settings {
	return Settings{
		Sources:    []Source{{ID: "miruro", Name: "Miruro", BaseURL: "https://www.miruro.cx"}},
		SelectedID: "miruro",
		ShowsSite:  "https://www.1shows.org",
		Track:      "SUB",
		AutoSkip:   true,
		SubtitleStyle: SubtitleStyle{
			Size: "M", Color: "White", Background: "Dim", Outline: true,
		},
	}
}

// SettingsStore caches the settings file.
type SettingsStore struct {
	mu    sync.Mutex
	path  string
	value Settings
	read  bool
}

// OpenSettings loads the settings file, or the defaults when it is missing.
func OpenSettings(dir string) *SettingsStore {
	return &SettingsStore{path: filepath.Join(dir, "settings.json")}
}

func (s *SettingsStore) load() {
	if s.read {
		return
	}
	s.value = DefaultSettings()
	readJSON(s.path, &s.value)
	if len(s.value.Sources) == 0 {
		s.value.Sources = DefaultSettings().Sources
	}
	if s.value.ShowsSite == "" {
		s.value.ShowsSite = DefaultSettings().ShowsSite
	}
	if s.value.Track == "" {
		s.value.Track = "SUB"
	}
	if s.value.SubtitleStyle.Size == "" {
		s.value.SubtitleStyle = DefaultSettings().SubtitleStyle
	}
	s.read = true
}

// Get returns a copy of the settings.
func (s *SettingsStore) Get() Settings {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	copy := s.value
	copy.Sources = append([]Source(nil), s.value.Sources...)
	return copy
}

// Update writes the settings and returns what was stored.
func (s *SettingsStore) Update(fn func(*Settings)) Settings {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	fn(&s.value)
	if len(s.value.Sources) == 0 {
		s.value.Sources = DefaultSettings().Sources
	}
	if s.value.SelectedID == "" {
		s.value.SelectedID = s.value.Sources[0].ID
	}
	_ = writeJSON(s.path, s.value)
	copy := s.value
	copy.Sources = append([]Source(nil), s.value.Sources...)
	return copy
}

// BaseURL is the selected Miruro mirror.
func (s *SettingsStore) BaseURL() string {
	settings := s.Get()
	for _, source := range settings.Sources {
		if source.ID == settings.SelectedID {
			return source.BaseURL
		}
	}
	if len(settings.Sources) > 0 {
		return settings.Sources[0].BaseURL
	}
	return DefaultSettings().Sources[0].BaseURL
}

// ShowsSite is the selected 1shows site.
func (s *SettingsStore) ShowsSite() string {
	return s.Get().ShowsSite
}

// Dub reports whether dubs are preferred.
func (s *SettingsStore) Dub() bool {
	return s.Get().Track == "DUB"
}
