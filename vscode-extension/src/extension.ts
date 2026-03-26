import * as fs from "fs";
import * as https from "https";
import * as path from "path";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

const VERSION = "1.0.0";
const REPO = "nguyenyou/unreal-scala-lsp";

let client: LanguageClient | undefined;

export async function activate(context: vscode.ExtensionContext) {
  // Allow manual override
  const config = vscode.workspace.getConfiguration("unrealScalaLsp");
  const manualPath = config.get<string>("serverPath", "");

  const serverPath = manualPath || (await ensureBinary(context));
  if (!serverPath) return;

  const serverOptions: ServerOptions = serverPath.endsWith(".jar")
    ? { command: "java", args: ["-jar", serverPath] }
    : { command: serverPath };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "scala" }],
  };

  client = new LanguageClient(
    "unrealScalaLsp",
    "Unreal Scala LSP",
    serverOptions,
    clientOptions
  );

  client.start();
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}

function getPlatformArtifact(): string | undefined {
  const platform = process.platform;
  const arch = process.arch;

  if (platform === "darwin" && arch === "arm64")
    return "unreal-scala-lsp-darwin-arm64";
  if (platform === "darwin" && arch === "x64")
    return "unreal-scala-lsp-darwin-x64";
  if (platform === "linux" && arch === "x64")
    return "unreal-scala-lsp-linux-x64";
  return undefined;
}

async function ensureBinary(
  context: vscode.ExtensionContext
): Promise<string | undefined> {
  const artifact = getPlatformArtifact();
  if (!artifact) {
    vscode.window.showErrorMessage(
      `unreal-scala-lsp: Unsupported platform ${process.platform}-${process.arch}. Set 'unrealScalaLsp.serverPath' manually.`
    );
    return undefined;
  }

  const binDir = path.join(context.globalStorageUri.fsPath, "bin");
  const binaryPath = path.join(binDir, `${VERSION}-${artifact}`);

  if (fs.existsSync(binaryPath)) {
    return binaryPath;
  }

  const url = `https://github.com/${REPO}/releases/download/v${VERSION}/${artifact}`;

  return vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: `Downloading Unreal Scala LSP v${VERSION}...`,
    },
    async () => {
      try {
        fs.mkdirSync(binDir, { recursive: true });
        await download(url, binaryPath);
        fs.chmodSync(binaryPath, 0o755);
        vscode.window.showInformationMessage(
          `Unreal Scala LSP v${VERSION} installed.`
        );
        return binaryPath;
      } catch (e) {
        vscode.window.showErrorMessage(`Failed to download: ${e}`);
        return undefined;
      }
    }
  );
}

function download(url: string, dest: string, redirects = 5): Promise<void> {
  return new Promise((resolve, reject) => {
    if (redirects <= 0) return reject(new Error("Too many redirects"));

    https
      .get(url, (res) => {
        if (
          res.statusCode &&
          res.statusCode >= 300 &&
          res.statusCode < 400 &&
          res.headers.location
        ) {
          return download(res.headers.location, dest, redirects - 1).then(
            resolve,
            reject
          );
        }
        if (res.statusCode !== 200) {
          return reject(new Error(`HTTP ${res.statusCode}`));
        }
        const file = fs.createWriteStream(dest);
        res.pipe(file);
        file.on("finish", () => file.close(() => resolve()));
        file.on("error", reject);
      })
      .on("error", reject);
  });
}
