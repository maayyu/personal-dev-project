# 開発環境ドキュメント

## 1. 開発環境

| 項目 | 内容 |
|---|---|
| OS | Windows 11 |
| IDE | Visual Studio Code |
| コンテナ実行基盤 | Docker Desktop（WSL2バックエンド） |
| 開発環境方式 | VS Code Dev Container |
| ソースコード管理 | Git / GitHub |
| 実装支援 | GitHub Copilot |

## 2. 採用技術

| 項目 | 採用内容 | 備考 |
|---|---|---|
| Java | 21 LTS | Spring Boot 4.1.xの必須要件（Java 17〜26対応）を満たす |
| Spring Boot | 4.1.x | - |
| ビルドツール | Maven（Maven Wrapper方式） | Mavenバイナリはコンテナに直接インストールせず、プロジェクト側の `mvnw` を通じて取得・実行する |
| ベースイメージ | `mcr.microsoft.com/devcontainers/java:21-trixie` | Microsoft公式Dev Container用イメージ。Debian 13 (trixie) ベース |

## 3. Dev Container構成

Windows 11（ホストOS）  
　├─ プロジェクトフォルダ（例：personal-dev-project）  
　│　　├─ .devcontainer/  
　│　　│　　├─ Dockerfile  
　│　　│　　└─ devcontainer.json  
　│　　├─ src/（今後作成）  
　│　　└─ mvnw（Spring Bootプロジェクト生成後に追加）  
　│  
　└─ Docker Desktop（WSL2バックエンド）  
　　　　└─ Dev Container  
　　　　　　└─ /workspaces/personal-dev-project/  
　　　　　　　　（ホスト側プロジェクトフォルダをマウント）


プロジェクトの実体（ソースコード）はホストOS側に存在し、コンテナはこれをマウント（借用）する形で動作する。コンテナを削除してもソースコードは失われない。

## 4. Dockerfileの役割

Dockerfileは「コンテナの中身」を定義する。

```dockerfile
FROM mcr.microsoft.com/devcontainers/java:21-trixie
```

Microsoft公式イメージにJava 21、非rootユーザー（`vscode`）、Git等の基本ツールが含まれているため、現時点ではこの1行のみで構成している。Maven本体は、Maven Wrapper方式を採用したため意図的にインストールしていない。Node.js、AWS CLI、DBクライアント等も、現時点で必要性がないため導入していない（必要になった時点で追加検討する）。

## 5. devcontainer.jsonの役割

devcontainer.jsonは、VS Codeがそのコンテナを開発環境としてどう利用するかを定義する。

```json
{
  "name": "personal-dev-project",
  "build": {
    "dockerfile": "Dockerfile"
  },
  "customizations": {
    "vscode": {
      "extensions": [
        "vscjava.vscode-java-pack",
        "vmware.vscode-boot-dev-pack"
      ]
    }
  },
  "forwardPorts": [8080],
  "remoteUser": "vscode"
}
```

| 設定項目 | 目的 |
|---|---|
| `name` | VS Code上での環境識別名 |
| `build.dockerfile` | 使用するDockerfileの指定 |
| `customizations.vscode.extensions` | コンテナ接続時に自動インストールする拡張機能の指定 |
| `forwardPorts` | 8080番ポートを明示的に転送対象として指定する設定。今回のSpring Boot動作確認のため明示的に採用した |
| `remoteUser` | 非rootユーザー（`vscode`）で動作させ、最小権限の原則に沿った運用とする |

## 6. VS Code拡張機能

devcontainer.jsonで指定した2つのExtension Packにより、以下が自動導入される。

- **Java関連**：Debugger for Java, Extension Pack for Java, Language Support for Java(TM) by Red Hat, Maven for Java, Project Manager for Java, Test Runner for Java, Gradle for Java 等
- **Spring Boot関連**：Spring Boot Dashboard, Spring Boot Tools, Spring Initializr Java Support 等

指定していない `ESLint`（Microsoft）拡張機能がコンテナ内にインストールされていることを確認した。現時点では原因未特定・開発への影響なしのため、対応せず記録のみとする。

## 7. 動作確認結果

| 確認項目 | コマンド | 結果 |
|---|---|---|
| Javaバージョン | `java -version` | `openjdk version "21.0.12" 2026-07-21 LTS` |
| 実行ユーザー | `whoami` | `vscode`（非root） |
| ワークスペースパス | `pwd` | `/workspaces/personal-dev-project` |
| マウント確認 | `ls -la` | `.devcontainer` フォルダがコンテナ内に反映されていることを確認 |
| 拡張機能導入 | VS Code拡張機能タブで目視確認 | Java/Spring Boot関連拡張機能がインストール済み |

## 8. トラブルシューティング

### 8-1. 文字コード問題

**事象**：`devcontainer.json` 内に日本語文字列（`"name": "個人開発プロジェクト"`）を記述し、PowerShellの `type` コマンドで内容を確認したところ、文字化けして表示された。

**原因**：VS Codeの保存時エンコーディング（UTF-8）と、PowerShellコンソールの表示エンコーディング（Shift-JIS想定）の不一致。ファイル自体は破損しておらず、表示上の問題であった。

**対応**：`devcontainer.json` 内の値を英数字（`"personal-dev-project"`）に変更し、表示エンコーディング差異の影響を受けない構成にした。

**再発防止策**：今後、Dev Container関連の設定ファイル（JSON等）には日本語を含めない方針とする。

### 8-2. WSL Integration問題

**事象**：`Reopen in Container` 実行時、以下のエラーでコンテナ起動に失敗した。

docker: Error response from daemon: accessing specified distro mount service:
stat /run/guest-services/distro-services/ubuntu.sock: no such file or directory


**調査の経緯**

1. **初期仮説（誤り）**：`wsl -l -v` で `Ubuntu` ディストリビューションが `Stopped` 状態だったことから、「ディストリビューションが停止しているため」と推定した。
2. **仮説の検証**：`wsl -d Ubuntu` でディストリビューションを起動状態にした上で再試行したが、同一のエラーが再現した。これにより初期仮説が誤りであったことが判明した。
3. **原因の再調査**：エラーメッセージおよびVS Code Dev Containers拡張機能の公式バグトラッカー（GitHub: microsoft/vscode-remote-release）を確認し、WSLg（GUI転送機能）用のWaylandソケットマウントに起因する既知の問題が複数報告されていることを把握した。
4. **真の原因の特定**：Docker Desktop側の設定（Settings → Resources → WSL Integration）で、「Ubuntu」ディストリビューションとの連携（Enable integration with additional distros）がOFFになっていることが、実際の画面確認により判明した。WSL自体はRunning状態であっても、Docker DesktopとWSLディストリビューション間の連携経路が確立していなかったため、コンテナが参照するソケットファイルにアクセスできなかった。

**対応**：Docker Desktop設定画面で「Ubuntu」のトグルをONにし、Apply & Restartを実行。再起動後、`Reopen in Container` が正常に成功した。

**再発防止策**：Dev Container導入時の確認項目に「Docker Desktop → Settings → Resources → WSL Integration で、使用するディストリビューションのトグルがONになっているか」を追加する。この確認は、本来Docker Desktop導入（TASK-006）の完了条件に含めるべき項目であった。

**教訓**：エラーメッセージの表面的な内容（distro mount serviceのエラー）から短絡的に原因を決めつけず、関連する状態（WSLのRunning/Stopped）を確認した上でも、実際にDocker Desktopの設定画面まで遡って確認する必要があった。今後のトラブルシューティングでも、複数の切り分け経路（コマンドでの状態確認、GUI設定の確認、公式情報の確認）を組み合わせる方針とする。

## 9. 今後の変更方針

- **Maven Wrapper（`mvnw`）**：Spring Bootプロジェクト生成後、Linuxコンテナ内での実行権限（execute権限）を確認する。問題があれば `devcontainer.json` に `postCreateCommand: "chmod +x mvnw"` を追加する。
- **DB構成**：DBをコンテナ化するか（docker-composeによるマルチコンテナ構成）は未確定。DB設計フェーズで改めて検討する。
- **AWS CLI等の追加ツール**：AWSデプロイフェーズ（Phase 9想定）に至った時点で、Dockerfileへの追加を検討する。
- **ESLint拡張機能の原因調査**：現時点では未対応。原因（依存関係によるものか、その他か）が判明次第、本ドキュメントに追記する。