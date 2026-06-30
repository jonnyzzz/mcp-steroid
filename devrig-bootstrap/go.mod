module github.com/jonnyzzz/mcp-steroid/devrig-bootstrap

go 1.23

// Pin the toolchain so the committed bin/bootstrap-* binaries are byte-reproducible
// across machines (the :claude-plugin drift guard byte-compares against a fresh build).
toolchain go1.26.4
