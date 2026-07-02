package main

import (
	"bufio"
	"encoding/json"
	"strings"
	"testing"
)

func TestInitializeHandshake(t *testing.T) {
	in := strings.NewReader(
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}` + "\n" +
			`{"jsonrpc":"2.0","method":"notifications/initialized"}` + "\n" +
			`{"jsonrpc":"2.0","id":2,"method":"tools/list"}` + "\n")
	var out strings.Builder
	if err := Serve(in, &out); err != nil {
		t.Fatalf("Serve: %v", err)
	}
	sc := bufio.NewScanner(strings.NewReader(out.String()))

	// 1st line: initialize result with serverInfo + capabilities.tools
	sc.Scan()
	var init rpcResponse
	if err := json.Unmarshal(sc.Bytes(), &init); err != nil {
		t.Fatalf("init resp: %v (line=%q)", err, sc.Text())
	}
	res := init.Result.(map[string]any)
	if res["serverInfo"] == nil || res["capabilities"] == nil {
		t.Fatalf("missing serverInfo/capabilities: %v", res)
	}

	// notifications/initialized produces NO response line.
	// 2nd line: tools/list with exactly one tool named devrig_status
	sc.Scan()
	var list rpcResponse
	if err := json.Unmarshal(sc.Bytes(), &list); err != nil {
		t.Fatalf("tools/list resp: %v (line=%q)", err, sc.Text())
	}
	tools := list.Result.(map[string]any)["tools"].([]any)
	if len(tools) != 1 || tools[0].(map[string]any)["name"] != "devrig_status" {
		t.Fatalf("expected one devrig_status tool, got %v", tools)
	}
}

func TestInitializeResultAdvertisesListChanged(t *testing.T) {
	res := initializeResult("2025-06-18")
	if res["protocolVersion"] != "2025-06-18" {
		t.Fatalf("must echo client protocol version, got %v", res["protocolVersion"])
	}
	caps, ok := res["capabilities"].(map[string]any)
	if !ok {
		t.Fatalf("capabilities missing/typed wrong: %v", res["capabilities"])
	}
	for _, prim := range []string{"tools", "resources", "prompts"} {
		p, ok := caps[prim].(map[string]any)
		if !ok || p["listChanged"] != true {
			t.Fatalf("%s must advertise listChanged=true, got %v", prim, caps[prim])
		}
	}
	// Empty version falls back to the baseline protocol.
	if initializeResult("")["protocolVersion"] != "2024-11-05" {
		t.Fatal("empty version must fall back to 2024-11-05")
	}
}

func TestHandleInitializeEchoesProtocolVersion(t *testing.T) {
	res, _ := handle(rpcRequest{Method: "initialize", ID: json.RawMessage(`1`),
		Params: json.RawMessage(`{"protocolVersion":"2025-11-05"}`)})
	if res.(map[string]any)["protocolVersion"] != "2025-11-05" {
		t.Fatalf("want echoed version, got %v", res.(map[string]any)["protocolVersion"])
	}
}
