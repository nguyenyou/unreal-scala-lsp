# Unreal Scala LSP

A Scala/Java language server providing go-to-definition and find-references.

## Build

Mill build tool. Run `./mill server.compile` to compile, `./mill server.assembly` for the fat jar.

## Architecture

Two provider backends behind the `LanguageProvider` trait:

- **AstProvider** (indexer module) — fast, AST-based, uses scalameta + javaparser. Default mode.
- **CompilerProvider** (compiler module) — uses Scala 3 presentation compiler. Enabled via `compilerPrecise` flag.

## Compiler-Precise Mode: Accuracy Above All

The `CompilerProvider` / compiler-precise path exists specifically to deliver **exact, compiler-accurate results**. This is the defining contract of the feature — if it's not precise, it has no reason to exist.

Rules for the compiler-precise code path:

- **No heuristics, no regex, no text matching.** Every symbol resolution must go through a proper parser or compiler API.
- **Use real parsers for source location finding.** Scalameta for `.scala` files, javac's parser (`com.sun.source.*`) for `.java` files — the same approach Metals uses.
- **Pay the cost for accuracy.** Slower startup, more memory, heavier dependencies are all acceptable tradeoffs. Speed is secondary to correctness.
- **If we can't resolve precisely, return nothing** rather than a wrong/approximate location. A miss is better than a lie.

This is what makes or breaks the feature. The AST provider already covers the "fast but approximate" use case.
