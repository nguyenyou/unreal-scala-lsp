# Release Process

This project has two release steps:

1. A tag-driven GitHub Release builds and publishes the LSP server jar.
2. The VS Code extension is packaged and published manually from a local checkout.

The extension downloads `unreal-scala-lsp.jar` from the GitHub Release whose tag matches the extension `VERSION`, so the server release must exist before publishing the extension.

## Prerequisites

- Java 25, matching `.github/workflows/release.yml`
- Bun
- GitHub CLI authenticated with push access
- VS Code Marketplace publishing access for the extension publisher

Check the local tools:

```bash
java -version
bun --version
gh auth status
```

## 1. Choose The Version

Use a `vX.Y.Z` Git tag. The same version number, without the leading `v`, must appear in three files:

- `server/src/unreallsp/server/Main.scala`
- `vscode-extension/src/extension.ts`
- `vscode-extension/package.json`

Example for `v1.7.0`:

```scala
log("Starting server v1.7.0")
```

```ts
const VERSION = "1.7.0";
```

```json
"version": "1.7.0"
```

Think of this as one label on three boxes. If one label is different, the extension can look for the wrong server jar.

## 2. Validate Locally

From the repository root:

```bash
./mill server.compile
./mill server.assembly
```

Build the VS Code extension bundle:

```bash
cd vscode-extension
bun install
bun run compile
bun run check
bunx @vscode/vsce package
cd ..
```

Optional smoke test with the local jar:

```bash
java -jar out/server/assembly.dest/out.jar --debug
```

Stop it with `Ctrl+C` after confirming it starts.

## 3. Commit The Version Bump

Commit the version changes and push them to `main` through the normal review flow.

Commit title format must use the repository prefix rules, for example:

```text
fix(server): release v1.7.0
```

Include the required co-author trailer:

```text
Co-authored-by: Codex GPT-5.5 (Extra High) <codex@openai.com>
```

## 4. Create The Server Release

After the version bump is merged to `main`, update local `main` and create the tag:

```bash
git switch main
git pull --ff-only origin main
git tag v1.7.0
git push origin v1.7.0
```

Pushing the tag starts `.github/workflows/release.yml`.

The workflow:

- checks out the tagged commit
- installs Java 25
- runs `./mill server.assembly`
- smoke-tests the assembly over LSP `initialize`
- copies `out/server/assembly.dest/out.jar` to `unreal-scala-lsp.jar`
- writes `checksums.txt`
- creates a GitHub Release with both files attached

Wait for the workflow to finish:

```bash
gh run list --workflow Release --limit 5
```

Verify the release assets:

```bash
gh release view v1.7.0 --json tagName,assets
```

The release must contain:

- `unreal-scala-lsp.jar`
- `checksums.txt`

## 5. Publish The VS Code Extension

Only publish the extension after the GitHub Release exists. The extension's first startup downloads:

```text
https://github.com/nguyenyou/unreal-scala-lsp/releases/download/vX.Y.Z/unreal-scala-lsp.jar
```

From the extension directory:

```bash
cd vscode-extension
bun install
bun run compile
bun run check
bunx @vscode/vsce package
bunx @vscode/vsce publish
```

If `vsce` needs a token, export it before publishing:

```bash
export VSCE_PAT=...
bunx @vscode/vsce publish
```

Do not publish the same extension version twice. The Marketplace rejects duplicate versions; bump `vscode-extension/package.json` first if a republish is needed.

## 6. Post-Release Checks

After publishing:

1. Install or update the extension from the VS Code Marketplace.
2. Open a Scala or Java workspace.
3. Enable debug logging if needed:

   ```json
   {
     "unrealScalaLsp.debug": true
   }
   ```

4. Confirm `Output -> Unreal Scala LSP` shows the new version and successful indexing.
5. Test go-to-definition and find-references in a small workspace.

## Recovery Notes

If the GitHub Release failed after pushing the tag, delete the failed release and tag only when you are sure no users consumed it:

```bash
gh release delete v1.7.0
git push origin :refs/tags/v1.7.0
git tag -d v1.7.0
```

Then fix the issue, create a new commit, and tag again.

If the server release succeeded but Marketplace publish failed, keep the GitHub Release. Fix the extension publishing issue and rerun only the local VS Code extension publish step.
