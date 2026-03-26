# mini-scala-lsp

Minimal Scala Language Server supporting **Go to Definition**.

Uses [Scalameta](https://scalameta.org/) to parse Scala files and index top-level definitions (class, trait, object, def, val, type, enum, given).

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
code --install-extension mini-scala-lsp-0.1.0.vsix
```

### Configure

1. Open VS Code Settings (Cmd+,)
2. Search for `miniScalaLsp.serverPath`
3. Set it to the native binary or assembly jar path:
   ```
   /path/to/mini-scala-lsp/out/server/nativeImage.dest/native-executable
   ```
   or:
   ```
   /path/to/mini-scala-lsp/out/server/assembly.dest/out.jar
   ```
4. Reload VS Code (Cmd+Shift+P -> "Developer: Reload Window")

### Usage

1. Open any folder containing `.scala` files
2. The server starts automatically when a `.scala` file is opened
3. **Cmd+click** (or F12) on a symbol name to Go to Definition

## Architecture

### Two-tier indexing

Inspired by [Metals](https://scalameta.org/metals/)' `ScalaToplevelMtags`:

- **Tier 1 — Token scan (batch indexing):** Uses Scalameta's tokenizer only (no AST). Scans for definition keywords (`class`, `trait`, `object`, etc.) followed by identifiers, tracking brace depth to stay at toplevel. Runs in parallel via virtual threads (Java 21+).
- **Tier 2 — Full parse (open files):** When a file is opened or changed (`didOpen`/`didChange`), does a full Scalameta AST parse for accurate positions.

### No lsp4j

The JSON-RPC transport is hand-rolled (~50 lines) using Content-Length framing over stdin/stdout, with [ujson](https://com-lihaoyi.github.io/upickle/) for JSON parsing. This eliminates lsp4j's reflection issues and makes GraalVM native-image work out of the box.

## Comparison with Metals

| Feature | mini-scala-lsp | Metals |
|---|---|---|
| Go to Definition (workspace) | Name-based matching | Compiler + SemanticDB + fallback chain |
| Go to Definition (dependencies) | - | Source JARs indexed via SemanticDB |
| Indexing strategy | Token scan (fast) + full parse (open files) | ScalaToplevelMtags (token scan) + SemanticDB |
| Type awareness | None (syntax only) | Full (presentation compiler) |
| Build tool integration | None needed | BSP (sbt, Mill, Gradle, etc.) |
| Startup time (native) | ~10ms | N/A (JVM only) |
| GraalVM native image | Yes | No |
| Dependencies | Scalameta + ujson | Scalameta + lsp4j + Coursier + BSP + ... |

## LSP Feature Coverage

| LSP Feature | Status | Notes |
|---|---|---|
| **General** | | |
| `initialize` / `shutdown` / `exit` | Supported | |
| **Text Document Sync** | | |
| `textDocument/didOpen` | Supported | Triggers full re-index of file |
| `textDocument/didChange` | Supported | Full sync mode |
| `textDocument/didClose` | Supported | No-op |
| `textDocument/didSave` | Supported | No-op |
| **Language Features** | | |
| `textDocument/definition` | Supported | Name-based, workspace only |
| `textDocument/declaration` | - | |
| `textDocument/typeDefinition` | - | |
| `textDocument/implementation` | - | |
| `textDocument/references` | - | |
| `textDocument/hover` | - | |
| `textDocument/completion` | - | |
| `textDocument/signatureHelp` | - | |
| `textDocument/documentHighlight` | - | |
| `textDocument/documentSymbol` | - | |
| `textDocument/codeAction` | - | |
| `textDocument/codeLens` | - | |
| `textDocument/formatting` | - | |
| `textDocument/rangeFormatting` | - | |
| `textDocument/onTypeFormatting` | - | |
| `textDocument/rename` | - | |
| `textDocument/prepareRename` | - | |
| `textDocument/foldingRange` | - | |
| `textDocument/selectionRange` | - | |
| `textDocument/semanticTokens` | - | |
| `textDocument/inlayHint` | - | |
| `textDocument/diagnostic` | - | |
| **Workspace** | | |
| `workspace/symbol` | - | |
| `workspace/didChangeConfiguration` | - | |
| `workspace/didChangeWatchedFiles` | - | |
| `workspace/executeCommand` | - | |

## Limitations

- Only indexes **workspace** sources — Go to Definition into library/dependency code is not supported
- Name-based matching — if multiple symbols share the same name, all locations are returned
- No type-awareness — the indexer parses syntax trees, not types, so it cannot resolve overloads or implicits

## Tech stack

- **Scala 3.8.2** / **Mill 1.1.5**
- [Scalameta](https://scalameta.org/) 4.15.2 — Scala tokenizer and parser
- [ujson](https://com-lihaoyi.github.io/upickle/) — JSON parsing (zero reflection)
- [vscode-languageclient](https://www.npmjs.com/package/vscode-languageclient) 9.0.1 — VS Code LSP client
- GraalVM native image support (optional)
