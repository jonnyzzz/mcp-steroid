package main

import (
	"encoding/json"
	"io"
	"os"
	"sync"
	"time"
)

// swapPollInterval is how often the watcher checks whether devrig finished installing.
// A package var (not const) so tests can drive swaps quickly.
var swapPollInterval = 750 * time.Millisecond

type proxy struct {
	home     string
	toClient *msgWriter
	clientIn *msgReader

	// startBackend/startHTTPBackend are injectable for tests; production uses the real spawners.
	startBackend     func(home, ver string) (*backend, error)
	startHTTPBackend func(mcpURL string, headers map[string]string, ver string) (*backend, error)

	// onSwapToDevrig, if set, is called once after a successful Tier-2 swap (used to remove the
	// transient status-line bar now that devrig is live).
	onSwapToDevrig func()

	mu       sync.Mutex
	backend  *backend
	tier     int // 0 none, 1 ide (HTTP), 2 devrig (stdio)
	protoVer string

	initOnce    sync.Once
	initialized chan struct{} // closed when the client sends notifications/initialized
	done        chan struct{} // closed when the client stream ends
}

func newProxy(in io.Reader, out io.Writer, home string) *proxy {
	return &proxy{
		home:             home,
		toClient:         newMsgWriter(out),
		clientIn:         newMsgReader(in),
		startBackend:     startBackend,
		startHTTPBackend: newHTTPBackend,
		protoVer:         "2024-11-05",
		initialized:      make(chan struct{}),
		done:             make(chan struct{}),
	}
}

// runProxy is the production entry point (main calls this).
func runProxy(in io.Reader, out io.Writer, home string) error {
	return newProxy(in, out, home).run()
}

func (p *proxy) run() error {
	go p.watchForIde()
	go p.watchForInstall()
	err := p.pumpClient()
	close(p.done)
	return err
}

// pumpClient reads client messages forever, dispatching by swap state.
func (p *proxy) pumpClient() error {
	for {
		msg, raw, err := p.clientIn.read()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}

		if msg.isNotification() && msg.Method == "notifications/initialized" {
			p.initOnce.Do(func() { close(p.initialized) })
		}

		p.mu.Lock()
		b := p.backend
		p.mu.Unlock()

		if b == nil {
			p.handleLocal(msg)
			continue
		}

		switch {
		case msg.isResponse():
			if orig, ok := stripIDPrefix(msg.ID); ok {
				p.write(b.writer, rewriteID(raw, orig))
			} else {
				p.write(b.writer, raw)
			}
		case msg.isRequest():
			if isDevrigStatusCall(msg) {
				p.handleLocal(msg)
			} else {
				p.write(b.writer, raw)
			}
		default: // notification
			p.write(b.writer, raw)
		}
	}
}

// handleLocal answers a client message from the bootstrap itself (pre-swap, or devrig_status).
func (p *proxy) handleLocal(msg rpcMessage) {
	if !msg.isRequest() {
		return // ignore client notifications locally
	}
	if msg.Method == "initialize" {
		var pp struct {
			ProtocolVersion string `json:"protocolVersion"`
		}
		if len(msg.Params) > 0 {
			_ = json.Unmarshal(msg.Params, &pp)
		}
		if pp.ProtocolVersion != "" {
			p.mu.Lock()
			p.protoVer = pp.ProtocolVersion
			p.mu.Unlock()
		}
		p.writeJSON(p.toClient, newResult(msg.ID, initializeResult(pp.ProtocolVersion)))
		return
	}
	result, isNotif := handle(rpcRequest{Method: msg.Method, ID: msg.ID, Params: msg.Params})
	if isNotif {
		return
	}
	p.writeJSON(p.toClient, newResult(msg.ID, result))
}

// watchForIde swaps to a running IDE's HTTP MCP endpoint (Tier 1) as soon as one is reachable,
// so the user has full IDE tools within seconds — before the devrig download finishes.
func (p *proxy) watchForIde() {
	select {
	case <-p.initialized:
	case <-p.done:
		return
	}
	t := time.NewTicker(swapPollInterval)
	defer t.Stop()
	for {
		p.mu.Lock()
		alreadySwapped := p.tier >= 1
		p.mu.Unlock()
		if alreadySwapped {
			return
		}
		for _, ep := range discoverIdeEndpoints(p.home) {
			if err := p.swapToIde(ep); err != nil {
				os.Stderr.WriteString("devrig-bootstrap: Tier-1 IDE swap failed for " + ep.mcpURL + ": " + err.Error() + "\n")
				continue
			}
			return // swapped
		}
		select {
		case <-t.C:
		case <-p.done:
			return
		}
	}
}

// watchForInstall swaps to the real devrig backend (Tier 2) once the download completes.
// Tier 2 supersedes Tier 1.
func (p *proxy) watchForInstall() {
	select {
	case <-p.initialized:
	case <-p.done:
		return
	}
	t := time.NewTicker(swapPollInterval)
	defer t.Stop()
	for {
		if installState(p.home) == "installed" {
			if err := p.swapToDevrig(); err != nil {
				os.Stderr.WriteString("devrig-bootstrap: Tier-2 devrig swap failed: " + err.Error() + "\n")
			}
			return
		}
		select {
		case <-t.C:
		case <-p.done:
			return
		}
	}
}

// swapToIde connects the HTTP backend and fires list_changed (Tier 1). No-op if a swap already happened.
func (p *proxy) swapToIde(ep ideEndpoint) error {
	p.mu.Lock()
	if p.tier >= 1 {
		p.mu.Unlock()
		return nil
	}
	ver := p.protoVer
	p.mu.Unlock()

	b, err := p.startHTTPBackend(ep.mcpURL, ep.headers, ver)
	if err != nil {
		return err
	}

	p.mu.Lock()
	if p.tier >= 1 { // lost a race with Tier 2 (or another IDE): discard this backend
		p.mu.Unlock()
		if b.shutdown != nil {
			b.shutdown()
		}
		return nil
	}
	p.backend = b
	p.tier = 1
	p.mu.Unlock()

	go p.pumpBackend(b)
	p.emitListChanged()
	os.Stderr.WriteString("devrig-bootstrap: Tier 1 active — bridged to running IDE at " + ep.mcpURL + "\n")
	return nil
}

// swapToDevrig connects the stdio devrig backend and fires list_changed (Tier 2), tearing down a
// Tier-1 HTTP backend if present. No-op if Tier 2 is already active.
func (p *proxy) swapToDevrig() error {
	p.mu.Lock()
	if p.tier >= 2 {
		p.mu.Unlock()
		return nil
	}
	ver := p.protoVer
	p.mu.Unlock()

	b, err := p.startBackend(p.home, ver)
	if err != nil {
		return err
	}

	p.mu.Lock()
	old := p.backend
	p.backend = b
	p.tier = 2
	p.mu.Unlock()

	if old != nil && old.shutdown != nil {
		old.shutdown() // stop the Tier-1 HTTP pump; its pumpBackend goroutine then exits on EOF
	}
	go p.pumpBackend(b)
	p.emitListChanged()
	os.Stderr.WriteString("devrig-bootstrap: Tier 2 active — swapped to devrig mcp\n")
	if p.onSwapToDevrig != nil {
		p.onSwapToDevrig()
	}
	return nil
}

// emitListChanged tells Claude to re-fetch tools/resources/prompts on its next turn.
func (p *proxy) emitListChanged() {
	// Claude re-fetches each list on the next turn (verified: it honors tools/list_changed).
	p.writeJSON(p.toClient, notif("notifications/tools/list_changed"))
	p.writeJSON(p.toClient, notif("notifications/resources/list_changed"))
	p.writeJSON(p.toClient, notif("notifications/prompts/list_changed"))
}

// pumpBackend forwards backend -> client until the backend closes.
func (p *proxy) pumpBackend(b *backend) {
	for {
		msg, raw, err := b.reader.read()
		if err != nil {
			if err != io.EOF {
				os.Stderr.WriteString("devrig-bootstrap: backend read error: " + err.Error() + "\n")
			}
			break
		}
		if msg.isRequest() {
			// server->client request: namespace its id so it can't collide with client ids.
			p.write(p.toClient, rewriteID(raw, addIDPrefix(msg.ID)))
		} else {
			p.write(p.toClient, raw)
		}
	}
	p.mu.Lock()
	if p.backend == b {
		p.backend = nil
		// Keep p.tier as-is: a superseding swap already advanced it; a Tier-1 exit before Tier 2
		// simply returns to local handling until watchForInstall swaps devrig in.
	}
	p.mu.Unlock()
	os.Stderr.WriteString("devrig-bootstrap: backend exited; local handling until the next swap\n")
}

func isDevrigStatusCall(msg rpcMessage) bool {
	if msg.Method != "tools/call" || len(msg.Params) == 0 {
		return false
	}
	var pp struct {
		Name string `json:"name"`
	}
	if err := json.Unmarshal(msg.Params, &pp); err != nil {
		return false
	}
	return pp.Name == "devrig_status"
}

func (p *proxy) write(w *msgWriter, raw []byte) {
	if err := w.writeRaw(raw); err != nil {
		os.Stderr.WriteString("devrig-bootstrap: write failed: " + err.Error() + "\n")
	}
}

func (p *proxy) writeJSON(w *msgWriter, v any) {
	if err := w.writeJSON(v); err != nil {
		os.Stderr.WriteString("devrig-bootstrap: write failed: " + err.Error() + "\n")
	}
}
