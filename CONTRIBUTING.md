# Contributing

## Prerequisites

- Java 17+
- [Mill](https://mill-build.org/) 1.1.5 (launcher included in repo)
- [Bun](https://bun.sh/) (for building the VS Code extension)

## Build

```bash
# Build the server fat jar
./mill server.assembly

# Build GraalVM native image (optional, much faster startup)
./mill server.nativeImage
```

## VS Code Extension

### Build and install

```bash
cd vscode-extension
bun install
bun run compile
bunx @vscode/vsce package
code --install-extension unreal-scala-lsp-1.0.0.vsix
```

### Configure

1. Open VS Code Settings (Cmd+,)
2. Search for `unrealScalaLsp.serverPath`
3. Set it to the native binary or assembly jar path:
   ```
   /path/to/unreal-scala-lsp/out/server/nativeImage.dest/native-executable
   ```
   or:
   ```
   /path/to/unreal-scala-lsp/out/server/assembly.dest/out.jar
   ```
4. Reload VS Code (Cmd+Shift+P -> "Developer: Reload Window")

## Architecture

### Two-tier indexing

Inspired by [Metals](https://scalameta.org/metals/)' `ScalaToplevelMtags`:

- **Tier 1 — Token scan (batch indexing):** Uses Scalameta's tokenizer only (no AST). Scans for definition keywords (`class`, `trait`, `object`, etc.) followed by identifiers, tracking brace depth to stay at toplevel. Runs in parallel via virtual threads (Java 21+).
- **Tier 2 — Full parse (open files):** When a file is opened or changed (`didOpen`/`didChange`), does a full Scalameta AST parse for accurate positions.

### No lsp4j

The JSON-RPC transport is hand-rolled (~50 lines) using Content-Length framing over stdin/stdout, with [ujson](https://com-lihaoyi.github.io/upickle/) for JSON parsing. This eliminates lsp4j's reflection issues and makes GraalVM native-image work out of the box.

## LSP Feature Coverage

Based on the [LSP 3.17 Specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/).

| LSP Feature | Status | Notes |
|---|---|---|
| **General** | | |
| `initialize` / `shutdown` / `exit` | Supported | |
| **Text Document Sync** | | |
| `textDocument/didOpen` | Supported | Triggers full re-index of file |
| `textDocument/didChange` | Supported | Full sync mode |
| `textDocument/didClose` | Supported | |
| `textDocument/didSave` | Supported | No-op |
| **Language Features** | | |
| `textDocument/definition` | Supported | Name-based, workspace only |
| `textDocument/references` | Supported | Text-based word-boundary search |
| **Workspace** | | |
| `workspace/didChangeWatchedFiles` | Supported | Re-indexes created/changed/deleted `.scala` files |

## Tech stack

- **Scala 3.8.2** / **Mill 1.1.5**
- [Scalameta](https://scalameta.org/) 4.15.2 — Scala tokenizer and parser
- [ujson](https://com-lihaoyi.github.io/upickle/) — JSON parsing (zero reflection)
- [vscode-languageclient](https://www.npmjs.com/package/vscode-languageclient) 9.0.1 — VS Code LSP client
- GraalVM native image support (optional)
