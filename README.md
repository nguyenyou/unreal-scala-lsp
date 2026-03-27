# unreal-scala-lsp

Source-only Scala language server. No compiler, no build server — just tokens and text.

## The idea

AI writes the code now. Claude Code with Opus 4.6, Cursor, Copilot — whatever your setup, the agent does the typing. It writes the function, generates the test, wires the module. Your job changed. You read the code. You read the diff. You decide if the change is right.

Think about what you actually do in an editor today. You open a file. You Cmd+click a symbol to understand where it comes from. You find references to see who uses it. You read. You navigate. You review. That's it.

Traditional language servers were built for a different era — one where humans typed every character. They need a compiler running, a build server connected, the whole project imported. They give you completions, signature help, inlay hints, code actions, diagnostics. Features that exist to help you *write*. All that machinery takes time to start, memory to run, and complexity to maintain.

But if you're not writing — if the AI is — you don't need any of that.

You need go-to-definition. You need find-references. You need it to start instantly and stay out of your way.

## What this is

A Scala language server that does exactly three things:

- **Go to Definition** — Cmd+click to jump to where a symbol is defined
- **Find References** — find every usage of a symbol across your workspace
- **File Watching** — auto-reindex when files change on disk (git checkout, AI edits, external tools)

That's the whole feature set. No completions. No diagnostics. No hover. No code actions. Just navigation.

It starts in ~10ms as a native binary. It indexes your workspace using [Scalameta](https://scalameta.org/)'s tokenizer — no compiler, no build tool integration, no SemanticDB. Open a folder, open a Scala file, start navigating.

## How it works

Two-tier indexing, inspired by [Metals](https://scalameta.org/metals/)' `ScalaToplevelMtags`:

1. **Token scan** — On startup, every `.scala` file is scanned using Scalameta's tokenizer (no AST). Looks for definition keywords (`class`, `trait`, `object`, `def`, `val`, `type`, `enum`, `given`) followed by identifiers, tracking brace depth. Runs in parallel via virtual threads. This is fast — sub-second for large codebases.

2. **Full parse** — When you open a file, it gets a full Scalameta AST parse for accurate positions. This is the upgrade path: open files get better data, closed files still have basic indexing.

The JSON-RPC transport is hand-rolled (~50 lines) over stdin/stdout. No lsp4j, no reflection — GraalVM native-image works out of the box.

## Trade-offs

This is not Metals. It doesn't try to be.

| | unreal-scala-lsp | Metals |
|---|---|---|
| Startup | ~10ms (native) | Seconds to minutes |
| Go to Definition | Name-based | Compiler + SemanticDB |
| Dependency navigation | No | Yes |
| Type awareness | None | Full |
| Build tool integration | None needed | Required (BSP) |
| GraalVM native image | Yes | No |

Name-based matching means if two symbols share the same name, you'll see both. No overload resolution, no implicit tracking. For most navigation, this is fine. When it's not, you'll know.

## Install

Install the [VS Code extension](https://marketplace.visualstudio.com/items?itemName=nguyenyou.unreal-scala-lsp), open a folder with `.scala` files, and start clicking.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, architecture details, and development setup.

## License

MIT
