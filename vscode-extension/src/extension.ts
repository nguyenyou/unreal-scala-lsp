import * as fs from "fs";
import * as https from "https";
import * as path from "path";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  RevealOutputChannelOn,
  ServerOptions,
} from "vscode-languageclient/node";

const VERSION = "1.4.0";
const REPO = "nguyenyou/unreal-scala-lsp";
const JAR_NAME = "unreal-scala-lsp.jar";

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel | undefined;

export async function activate(context: vscode.ExtensionContext) {
  // Allow manual override
  const config = vscode.workspace.getConfiguration("unrealScalaLsp");
  const manualPath = config.get<string>("serverPath", "");
  const debug = config.get<boolean>("debug", false);

  const serverPath = manualPath || (await ensureJar(context));
  if (!serverPath) return;

  outputChannel = vscode.window.createOutputChannel("Unreal Scala LSP");

  const isJar = serverPath.endsWith(".jar");
  const args: string[] = [];
  if (isJar) {
    args.push("-jar", serverPath);
  }
  if (debug) {
    args.push("--debug");
  }

  const serverOptions: ServerOptions = isJar
    ? { command: "java", args }
    : { command: serverPath, args: debug ? ["--debug"] : [] };

  const usePresentationCompiler = config.get<boolean>("usePresentationCompiler", true);

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: "file", language: "scala" },
      { scheme: "file", language: "java" },
    ],
    initializationOptions: {
      usePresentationCompiler,
      debug,
    },
    outputChannel,
    revealOutputChannelOn: debug
      ? RevealOutputChannelOn.Info
      : RevealOutputChannelOn.Never,
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

async function ensureJar(
  context: vscode.ExtensionContext
): Promise<string | undefined> {
  const binDir = path.join(context.globalStorageUri.fsPath, "bin");
  const jarPath = path.join(binDir, `${VERSION}-${JAR_NAME}`);

  if (fs.existsSync(jarPath)) {
    return jarPath;
  }

  const url = `https://github.com/${REPO}/releases/download/v${VERSION}/${JAR_NAME}`;

  return vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: `Downloading Unreal Scala LSP v${VERSION}...`,
    },
    async () => {
      try {
        fs.mkdirSync(binDir, { recursive: true });
        await download(url, jarPath);
        vscode.window.showInformationMessage(
          `Unreal Scala LSP v${VERSION} installed.`
        );
        return jarPath;
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
