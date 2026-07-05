package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

func claudeSettingsPath(home string) string {
	return filepath.Join(home, ".claude", "settings.json")
}

func statuslineOwnerPath(home string) string {
	return filepath.Join(markersDir(home), "statusline.owner")
}

func writeStatuslineOwner(home, mode string) {
	_ = os.MkdirAll(markersDir(home), 0o755)
	if err := os.WriteFile(statuslineOwnerPath(home), []byte(mode), 0o644); err != nil {
		os.Stderr.WriteString("devrig-bootstrap: write statusline.owner: " + err.Error() + "\n")
	}
}

func statuslineOwner(home string) string {
	b, err := os.ReadFile(statuslineOwnerPath(home))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// fileHasStatusLine reports whether path parses as JSON with a "statusLine" key.
// The bool "parsed" is false when the file exists but does not parse (caller decides how to treat it).
func fileHasStatusLine(path string) (has bool, parsed bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return false, true // absent == cleanly "no statusLine"
	}
	var m map[string]json.RawMessage
	if json.Unmarshal(b, &m) != nil {
		return false, false // present but unparseable
	}
	_, ok := m["statusLine"]
	return ok, true
}

// shouldUseHookMode is true when we must NOT install a bar: a statusLine already exists in user,
// project, or local settings, or the user settings file is present-but-unparseable.
func shouldUseHookMode(home, cwd string) bool {
	if has, parsed := fileHasStatusLine(claudeSettingsPath(home)); has || !parsed {
		return true
	}
	for _, p := range []string{
		filepath.Join(cwd, ".claude", "settings.json"),
		filepath.Join(cwd, ".claude", "settings.local.json"),
	} {
		if has, _ := fileHasStatusLine(p); has {
			return true
		}
	}
	return false
}

// ourStatusLine builds the statusLine block pointing at this bootstrap binary.
func ourStatusLine(selfPath string) map[string]any {
	return map[string]any{
		"type":            "command",
		"command":         selfPath + " " + statuslineFlag,
		"padding":         0,
		"refreshInterval": 2,
	}
}

// installStatusLine adds our statusLine to the user settings.json when none is present. Atomic,
// idempotent, preserves all other keys. No-op if a statusLine (foreign or ours) already exists.
func installStatusLine(home, selfPath string) error {
	path := claudeSettingsPath(home)
	m, err := loadSettingsMap(path)
	if err != nil {
		return err
	}
	if _, ok := m["statusLine"]; ok {
		return nil // foreign or ours already there — never overwrite
	}
	sl, _ := json.Marshal(ourStatusLine(selfPath))
	m["statusLine"] = sl
	return writeSettingsAtomic(path, m)
}

// removeStatusLine deletes the statusLine key iff it is ours (command contains statuslineFlag). Atomic.
func removeStatusLine(home string) error {
	path := claudeSettingsPath(home)
	m, err := loadSettingsMap(path)
	if err != nil || m == nil {
		return err
	}
	raw, ok := m["statusLine"]
	if !ok {
		return nil
	}
	if !bytes.Contains(raw, []byte(statuslineFlag)) {
		return nil // foreign — leave it
	}
	delete(m, "statusLine")
	return writeSettingsAtomic(path, m)
}

// loadSettingsMap reads path into an ordered-agnostic map. A missing file yields an empty map;
// an unparseable existing file is an error (callers must not clobber it).
func loadSettingsMap(path string) (map[string]json.RawMessage, error) {
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return map[string]json.RawMessage{}, nil
	}
	if err != nil {
		return nil, err
	}
	m := map[string]json.RawMessage{}
	if len(bytes.TrimSpace(b)) == 0 {
		return m, nil
	}
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, err
	}
	return m, nil
}

// writeSettingsAtomic writes m as pretty JSON to path via a temp file + rename in the same directory.
func writeSettingsAtomic(path string, m map[string]json.RawMessage) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	out, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".settings-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(append(out, '\n')); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return err
	}
	return os.Rename(tmpName, path)
}
