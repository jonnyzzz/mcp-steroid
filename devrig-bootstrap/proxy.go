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

	// startBackend is injectable for tests; production uses the real process spawner.
	startBackend func(home, ver string) (*backend, error)

	mu       sync.Mutex
	backend  *backend
	protoVer string

	initOnce    sync.Once
	initialized chan struct{} // closed when the client sends notifications/initialized
	done        chan struct{} // closed when the client stream ends
}

func newProxy(in io.Reader, out io.Writer, home string) *proxy {
	return &proxy{
		home:         home,
		toClient:     newMsgWriter(out),
		clientIn:     newMsgReader(in),
		startBackend: startBackend,
		protoVer:     "2024-11-05",
		initialized:  make(chan struct{}),
		done:         make(chan struct{}),
	}
}

// runProxy is the production entry point (main calls this).
func runProxy(in io.Reader, out io.Writer, home string) error {
	return newProxy(in, out, home).run()
}

func (p *proxy) run() error {
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

// watchForInstall waits for the session to initialize, then swaps as soon as devrig is installed.
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
			if err := p.swap(); err != nil {
				os.Stderr.WriteString("devrig-bootstrap: proxy swap failed: " + err.Error() + "\n")
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

// swap connects the real devrig backend and tells the client the lists changed.
func (p *proxy) swap() error {
	p.mu.Lock()
	if p.backend != nil {
		p.mu.Unlock()
		return nil
	}
	ver := p.protoVer
	p.mu.Unlock()

	b, err := p.startBackend(p.home, ver)
	if err != nil {
		return err
	}

	go p.pumpBackend(b)

	p.mu.Lock()
	p.backend = b
	p.mu.Unlock()

	// Claude re-fetches each list on the next turn (verified: it honors tools/list_changed).
	p.writeJSON(p.toClient, notif("notifications/tools/list_changed"))
	p.writeJSON(p.toClient, notif("notifications/resources/list_changed"))
	p.writeJSON(p.toClient, notif("notifications/prompts/list_changed"))
	return nil
}

// pumpBackend forwards backend -> client until the backend closes.
func (p *proxy) pumpBackend(b *backend) {
	for {
		msg, raw, err := b.reader.read()
		if err != nil {
			if err != io.EOF {
				os.Stderr.WriteString("devrig-bootstrap: backend read error: " + err.Error() + "\n")
			}
			return
		}
		if msg.isRequest() {
			// server->client request: namespace its id so it can't collide with client ids.
			p.write(p.toClient, rewriteID(raw, addIDPrefix(msg.ID)))
		} else {
			p.write(p.toClient, raw)
		}
	}
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
