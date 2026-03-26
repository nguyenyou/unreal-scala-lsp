import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;

export function activate(context: vscode.ExtensionContext) {
  const config = vscode.workspace.getConfiguration("miniScalaLsp");
  const serverPath = config.get<string>("serverPath", "");

  if (!serverPath) {
    vscode.window.showErrorMessage(
      "mini-scala-lsp: Set 'miniScalaLsp.serverPath' to the native binary or assembly jar path."
    );
    return;
  }

  const serverOptions: ServerOptions = serverPath.endsWith(".jar")
    ? { command: "java", args: ["-jar", serverPath] }
    : { command: serverPath };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "scala" }],
  };

  client = new LanguageClient(
    "miniScalaLsp",
    "Mini Scala LSP",
    serverOptions,
    clientOptions
  );

  client.start();
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}
