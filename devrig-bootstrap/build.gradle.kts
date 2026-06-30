plugins { base }

// (GOOS, GOARCH, output suffix) for every target the canonical installer supports.
val targets = listOf(
    Triple("darwin", "arm64", ""),
    Triple("darwin", "amd64", ""),
    Triple("linux", "amd64", ""),
    Triple("linux", "arm64", ""),
    Triple("windows", "amd64", ".exe"),
    Triple("windows", "arm64", ".exe"),
)

val outDir = layout.buildDirectory.dir("bin")

val buildBootstrapBinaries = tasks.register("buildBootstrapBinaries") {
    group = "devrig-bootstrap"
    description = "Cross-compile the Go bootstrap for every target"
    inputs.dir(projectDir.resolve("."))      // .go + go.mod
    outputs.dir(outDir)

    doLast {
        // Fail loudly if Go is absent — no silent fallback.
        val goExe = System.getenv("GOROOT")?.let { "$it/bin/go" }?.takeIf { file(it).exists() } ?: "go"
        val probe = ProcessBuilder(goExe, "version").redirectErrorStream(true).start()
        val probeOut = probe.inputStream.bufferedReader().readText()
        if (probe.waitFor() != 0) error("Go toolchain not found on PATH (or GOROOT). Install Go 1.23+. Probe output:\n$probeOut")

        val dest = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        targets.forEach { (os, arch, suffix) ->
            val bin = dest.resolve("bootstrap-$os-$arch$suffix")
            // Byte-reproducible across rebuilds (same toolchain) so :claude-plugin can verify the
            // committed bin/bootstrap-* are not stale: -buildid= drops the build id, and
            // -buildvcs=false stops Go embedding the git revision/dirty state (which would otherwise
            // change the bytes on every commit).
            val pb = ProcessBuilder(goExe, "build", "-trimpath", "-buildvcs=false", "-ldflags", "-s -w -buildid=", "-o", bin.absolutePath, ".")
                .directory(projectDir)
                .redirectErrorStream(true)
            pb.environment()["GOOS"] = os
            pb.environment()["GOARCH"] = arch
            pb.environment()["CGO_ENABLED"] = "0"
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("go build failed for $os/$arch:\n$log")
            if (!bin.exists()) throw GradleException("go build produced no binary for $os/$arch")
        }
    }
}

// Expose the binaries to other modules WITHOUT cross-build/ access.
val bootstrapBinaries by configurations.creating { isCanBeResolved = false; isCanBeConsumed = true }
artifacts { add(bootstrapBinaries.name, outDir) { builtBy(buildBootstrapBinaries) } }

tasks.named("assemble") { dependsOn(buildBootstrapBinaries) }
