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
```

The fat jar is produced at `out/server/assembly.dest/out.jar`.

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
2. Search for `miniScalaLsp.serverJar`
3. Set it to the absolute path of the assembly jar, e.g.:
   ```
   /path/to/mini-scala-lsp/out/server/assembly.dest/out.jar
   ```
4. Reload VS Code (Cmd+Shift+P -> "Developer: Reload Window")

### Usage

1. Open any folder containing `.scala` files
2. The server starts automatically when a `.scala` file is opened
3. **Cmd+click** (or F12) on a symbol name to Go to Definition

## How it works

1. On `initialize`, the server walks workspace directories and parses all `.scala` files with Scalameta
2. It builds an in-memory index mapping symbol names to their source locations
3. On `textDocument/definition`, it extracts the word under the cursor and looks it up in the index
4. Files are re-indexed on open/change via `textDocument/didOpen` and `textDocument/didChange`

## Limitations

- Only indexes **workspace** sources — Go to Definition into library/dependency code is not supported
- Name-based matching — if multiple symbols share the same name, all locations are returned
- No type-awareness — the indexer parses syntax trees, not types, so it cannot resolve overloads or implicits

## Tech stack

- **Scala 3.8.2** / **Mill 1.1.5**
- [LSP4J](https://github.com/eclipse-lsp4j/lsp4j) 0.23.1 — LSP protocol implementation
- [Scalameta](https://scalameta.org/) 4.15.2 — Scala parser
- [vscode-languageclient](https://www.npmjs.com/package/vscode-languageclient) 9.0.1 — VS Code LSP client
