# Unreal Scala LSP

AI writes the code. You read it, review it, navigate it. This is the language server for that workflow.

## The problem

Traditional language servers were built for an era where humans typed every character. They spin up a compiler, connect to a build server, import the whole project — all to give you completions, diagnostics, signature help, code actions. Features that exist to help you *write*.

But you're not writing anymore. Claude Code, Cursor, Copilot — the agent writes the function, generates the test, wires the module. Your job is to read the code, read the diff, Cmd+click into a definition to understand what changed, find references to see what's affected. That's it.

You don't need a compiler running for that. You don't need a build server. You definitely don't need to wait 30 seconds for the project to import before you can navigate.

## What this gives you

- **Go to Definition** — Cmd+click (or F12) to jump to where a symbol is defined
- **Find References** — find every usage of a symbol across your workspace
- **File Watching** — auto-reindex when files change on disk (git checkout, AI edits, branch switches)

That's the whole feature set. Nothing else. On purpose.

## What it feels like

Install. Open a folder. Open a `.scala` file. Start clicking.

The server starts in ~10ms as a native binary. It indexes your workspace in parallel using [Scalameta](https://scalameta.org/)'s tokenizer — no compiler, no build tool, no setup. By the time your file is open, the index is ready.

When the AI rewrites a file, the index updates. When you switch branches, it re-scans. When you delete a file, it cleans up. Silently, instantly.

## Trade-offs

This is not Metals. It doesn't try to be.

No type awareness. No dependency navigation. No overload resolution. Name-based matching means if two symbols share the same name, you'll see both.

For reading and navigating code — which is what you're actually doing now — this is enough. When it's not, you'll know.

## Links

- [GitHub](https://github.com/nguyenyou/unreal-scala-lsp)
- [Issues](https://github.com/nguyenyou/unreal-scala-lsp/issues)
