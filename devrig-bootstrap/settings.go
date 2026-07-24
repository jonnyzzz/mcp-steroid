package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
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

// fileStatusLine inspects a settings file's statusLine. `present` is true if a statusLine key exists;
// `ours` is true if that statusLine is the devrig bar (command carries the statuslineFlag sentinel);
// `parsed` is false when the file exists but does not parse (caller decides how to treat that).
func fileStatusLine(path string) (present, ours, parsed bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return false, false, true // absent == cleanly "no statusLine"
	}
	var m map[string]json.RawMessage
	if json.Unmarshal(b, &m) != nil {
		return false, false, false // present but unparseable
	}
	raw, ok := m["statusLine"]
	if !ok {
		return false, false, true
	}
	return true, bytes.Contains(raw, []byte(statuslineFlag)), true
}

// shouldUseHookMode is true when we must NOT install a bar: a FOREIGN statusLine already exists in user,
// project, or local settings, or the user settings file is present-but-unparseable. Our OWN bar does not
// count — otherwise re-asserting it (to nudge a late-arming watcher) would flip us into hook mode.
func shouldUseHookMode(home, cwd string) bool {
	present, ours, parsed := fileStatusLine(claudeSettingsPath(home))
	if !parsed {
		return true // don't risk clobbering an unparseable user file
	}
	if present && !ours {
		return true // a foreign user status line
	}
	for _, p := range []string{
		filepath.Join(cwd, ".claude", "settings.json"),
		filepath.Join(cwd, ".claude", "settings.local.json"),
	} {
		if pr, _, _ := fileStatusLine(p); pr {
			return true // any project/local status line is foreign to us
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

// installStatusLine ensures our statusLine is present in the user settings.json. If none exists it adds
// ours; if ours is already there it RE-WRITES it (atomic temp+rename bumps the file so an instance whose
// settings-watcher armed after the initial write still gets a change event and renders the bar); a
// FOREIGN statusLine is never touched. Atomic, preserves all other keys.
func installStatusLine(home, selfPath string) error {
	path := claudeSettingsPath(home)
	m, err := loadSettingsMap(path)
	if err != nil {
		return err
	}
	if raw, ok := m["statusLine"]; ok && !bytes.Contains(raw, []byte(statuslineFlag)) {
		return nil // foreign status line — never overwrite
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
