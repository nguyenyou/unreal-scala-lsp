# mini-scala-lsp

Minimal Scala Language Server supporting **Go to Definition**.

Uses [Scalameta](https://scalameta.org/) to parse Scala files and index top-level definitions (class, trait, object, def, val, type, enum, given).

## Build

```bash
./mill server.assembly
```

The fat jar is produced at `out/server/assembly.dest/out.jar`.

## VS Code Extension

```bash
cd vscode-extension
bun install
bun run compile
bunx @vscode/vsce package
```

Install the `.vsix` file in VS Code, then set `miniScalaLsp.serverJar` to the path of the assembly jar.

## How it works

1. On `initialize`, the server walks workspace directories and parses all `.scala` files with Scalameta
2. It builds an in-memory index mapping symbol names to their source locations
3. On `textDocument/definition`, it extracts the word under the cursor and looks it up in the index
4. Files are re-indexed on open/change via `textDocument/didOpen` and `textDocument/didChange`
