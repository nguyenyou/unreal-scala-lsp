import * as path from "path";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;

export function activate(context: vscode.ExtensionContext) {
  const config = vscode.workspace.getConfiguration("miniScalaLsp");
  const jarPath = config.get<string>("serverJar", "");

  if (!jarPath) {
    vscode.window.showErrorMessage(
      "mini-scala-lsp: Set 'miniScalaLsp.serverJar' to the path of the server assembly jar."
    );
    return;
  }

  const serverOptions: ServerOptions = {
    command: "java",
    args: ["-jar", jarPath],
  };

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
