package subtitles

import "testing"

const sampleSRT = "\ufeff1\r\n00:00:01,000 --> 00:00:02,500\r\nHello <i>world</i>\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond line\r\n"

func TestToVTTConvertsSRT(t *testing.T) {
	vtt := ToVTT(sampleSRT)
	if !hasPrefix(vtt, "WEBVTT") {
		t.Fatalf("not VTT: %q", vtt)
	}
	if !contains(vtt, "00:00:01.000 --> 00:00:02.500") {
		t.Fatalf("timing not converted: %q", vtt)
	}
}

func TestToVTTLeavesVTTAlone(t *testing.T) {
	vtt := "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n"
	if got := ToVTT(vtt); got != vtt {
		t.Fatalf("VTT was rewritten: %q", got)
	}
}

func TestParseCuesAndAt(t *testing.T) {
	cues := Parse(ToVTT(sampleSRT))
	if len(cues) != 2 {
		t.Fatalf("cues = %+v", cues)
	}
	if cues[0].Text != "Hello world" {
		t.Fatalf("first cue = %q", cues[0].Text)
	}
	cue := At(cues, 1.5)
	if cue == nil || cue.Text != "Hello world" {
		t.Fatalf("cue at 1.5s = %+v", cue)
	}
	if At(cues, 2.75) != nil {
		t.Fatal("the gap between cues returned one")
	}
}

func TestNormalizeLanguageCodes(t *testing.T) {
	cases := map[string]string{
		"fre": "fr", "fra": "fr", "eng": "en", "ger": "de",
		"pt-BR": "pt-br", "en-US": "en", "jpn": "ja", "": "",
	}
	for input, want := range cases {
		if got := Normalize(input); got != want {
			t.Errorf("Normalize(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestLanguageName(t *testing.T) {
	if got := LanguageName("fr"); got != "French" {
		t.Fatalf("name = %q", got)
	}
	if got := LanguageName("xx"); got != "Xx" {
		t.Fatalf("fallback = %q", got)
	}
}

func hasPrefix(value, prefix string) bool { return len(value) >= len(prefix) && value[:len(prefix)] == prefix }
func contains(value, part string) bool {
	for i := 0; i+len(part) <= len(value); i++ {
		if value[i:i+len(part)] == part {
			return true
		}
	}
	return false
}
