# java_MyAI Agent2

JavaFX で構築したデスクトップ向け AI チャットエージェントです。  
OpenAI 互換エンドポイントに接続し、ツール呼び出し（検索・Git参照・ファイルI/O・ローカルコマンド）を安全制約付きで実行できます。  
会話履歴の保存、システムプロンプト編集、ストリーミング表示にも対応しています。
Office/PDF 等のバイナリ読込にも対応し、抽出可能な形式は本文テキストとして扱えます。  
WebView で会話のHTMLプレビュー表示が可能で、セッション管理により複数の独立した会話を同時管理できます。

## 目次

- [java\_MyAI Agent2](#java_myai-agent2)
  - [目次](#目次)
  - [プロジェクト概要](#プロジェクト概要)
  - [主な機能](#主な機能)
  - [技術スタック](#技術スタック)
  - [動作環境](#動作環境)
  - [セットアップ](#セットアップ)
  - [起動方法](#起動方法)
  - [addons ディレクトリ運用](#addons-ディレクトリ運用)
  - [使い方](#使い方)
    - [セッション管理](#セッション管理)
      - [セッション作成](#セッション作成)
      - [セッション選択・切り替え](#セッション選択切り替え)
      - [セッションの保存・管理](#セッションの保存管理)
    - [基本操作](#基本操作)
    - [手動ツール実行](#手動ツール実行)
    - [ディレクトリ切替](#ディレクトリ切替)
  - [ツール一覧](#ツール一覧)
    - [手動実行（`/tool`）](#手動実行tool)
    - [LLM 自動実行（Function Calling）](#llm-自動実行function-calling)
  - [設定（環境変数）](#設定環境変数)
    - [プロバイダー別設定](#プロバイダー別設定)
      - [OpenAI（優先プロバイダー）](#openai優先プロバイダー)
      - [Google Gemini](#google-gemini)
    - [共通設定](#共通設定)
    - [設定例（PowerShell）](#設定例powershell)
      - [OpenAI を使用する場合](#openai-を使用する場合)
      - [Gemini を使用する場合](#gemini-を使用する場合)
      - [複数プロバイダーを設定した場合](#複数プロバイダーを設定した場合)
    - [後方互換性（非推奨）](#後方互換性非推奨)
  - [配布物の作成方法](#配布物の作成方法)
  - [テスト](#テスト)
  - [プロジェクト構成](#プロジェクト構成)
  - [安全対策・制約](#安全対策制約)
  - [トラブルシューティング](#トラブルシューティング)
  - [ライセンス](#ライセンス)

## プロジェクト概要

java_MyAI Agent2 は、ローカル開発支援を目的としたデスクトップ AI エージェントです。  
特徴は以下です。

- JavaFX ベースの軽量 GUI
- OpenAI 互換 API への接続
- LangChain4j 1.12.2 による効率的な Agent 実装
- ツール実行を伴う回答生成（Function Calling）
- セッション管理による複数会話の並立
- 会話履歴の永続化（JSON）
- ストリーミング応答表示（非対応エンドポイント時は非ストリーミングへフォールバック）
- WebView によるプレビュー表示

## 主な機能

- チャット UI
  - 複数行入力
  - Ctrl+Enter で送信
  - Shift+Enter で改行
  - WebView で会話プレビュー表示（HTMLレンダリング）
  - ツール実行結果の折りたたみ表示
  - 左ペイン内にセッション一覧を表示
- セッション管理
  - 複数会話の独立管理
  - セッション作成時にモデル選択
  - セッションタイトルの自動推定（最初のメッセージから）
  - セッションの切り替え・保存・読み込み
  - セッション単位での作業ディレクトリ管理
- 応答生成
  - 非同期処理（UI ブロック回避）
  - ストリーミング逐次表示
  - ストリーミング失敗時の同期フォールバック
- ツール実行
  - 手動実行（`/tool` コマンド）
  - LLM 自動実行（Function Calling、最大3ラウンド）
- 開発支援
  - ワークスペース内検索（ripgrep/grep 併用）
  - Git 履歴参照（read-only）
  - ファイル読み書き
  - Excel の範囲読取
  - バイナリファイル読取（Office/PDF/画像）
  - ローカルコマンド実行（許可制）
- 運用機能
  - 会話履歴の保存・再読込（JSON ファイル）
  - システムプロンプトの編集
  - セッション単位の作業ディレクトリ切り替え

## 技術スタック

- 言語: Java 21
- ビルド: Gradle
- UI: JavaFX 21.0.3（controls / fxml / media / web）
- LLM 連携: LangChain4j 1.12.2（`Assistant` インターフェース、`@Tool` アノテーション）
- Function Calling: OpenAI互換API の `tools` パラメータ対応
- 会話履歴: `MessageWindowChatMemory` による自動管理
- JSON: Gson 2.10.1
- 文書抽出: Apache POI 5.4.1 / Apache Tika 2.9.2
- ログ: Log4j2 2.24.3 + SLF4J
- テスト: JUnit 4

## 動作環境

- OS: Windows を主対象（他 OS でも Java 実行環境があれば動作可能）
- JDK: 21
- ネットワーク: OpenAI 互換 API に到達可能
- 任意: Git コマンド利用時は Git が PATH 上に存在

補足:

- Windows ARM64 環境では JavaFX プラグイン非依存の構成を採用しています。
- 実行時は JavaFX モジュール（controls/fxml/media/web）を JVM 引数で明示して起動します。

## セットアップ

1. リポジトリを取得
2. プロジェクトルートへ移動
3. 必要に応じて環境変数を設定
4. 依存解決とビルドを実行

```bash
gradlew :app:build
```

## 起動方法

開発起動:

```bash
gradlew :app:run
```

起動後、GUI 画面でチャットを開始できます。

## addons ディレクトリ運用

外部コマンド実行ファイル（`rg`, `grep`, `nkf` など）を同梱したい場合は、`addons` ディレクトリに配置します。

- 推奨配置先
  - アプリ起動ディレクトリ配下の `addons`
- 代表例
  - `addons/rg.exe`
  - `addons/grep.exe`
  - `addons/nkf.exe`

解決順の概要:

- `WorkspaceGrepTool`（`/tool grep`）
  - ワークスペース直下の `addons/rg(.exe)`
  - 次にアプリ起動ディレクトリ直下の `addons/rg(.exe)`
  - 最後に PATH 上の `rg`
- `LocalCommandTool`（`/tool cmd` / `localcmd`）
  - アプリ起動時に固定した `addons` を優先
  - 見つからない場合は PATH / Git Bash 側を利用

## 使い方

### セッション管理

#### セッション作成

- 左ペインの「+ 新規」ボタンをクリック
- ダイアログでプロバイダー（OpenAI / Gemini）とモデルを選択
- 新しいセッションが作成され、会話を開始できます

#### セッション選択・切り替え

- 左ペインのセッション一覧に全セッションが表示されます
- セッション名をクリックして選択・切り替え
- 選択したセッションの会話履歴が自動的に読み込まれます
- セッション切り替え時に、そのセッションで設定されたプロバイダー・モデルが自動で適用されます

#### セッションの保存・管理

- セッションはタイトル付きで自動的に JSON ファイルで保存されます
- セッション名はタイトルと一致し、最初の user メッセージ（最大32文字）から自動推定
- セッション単位で作業ディレクトリを管理でき、セッション切り替え時に復元されます
- 会話履歴は復元時に自動的に読み込まれます

### 基本操作

- メッセージ入力後に送信
- 応答中は逐次的にテキストが表示
- ツール実行結果は折りたたみ表示（クリックで展開）
- 完了時に整形済み表示へ更新

### 手動ツール実行

- 先頭に `/tool` を付けて実行
- 例:
  - `/tool time`
  - `/tool grep キーワード`
  - `/tool gitlog App.java`
  - `/tool gitshow <ref>`
  - `/tool readfile 相対パス`
  - `/tool readexcel sample.xlsx Sheet1 A1:C10`
  - `/tool readbinary docs/spec.pdf`
  - `/tool setdir ディレクトリパス`
  - `/tool getdir`

### ディレクトリ切替

- `/tool setdir` で作業基点を変更（セッション単位）
- `/tool getdir` で現在値を確認
- ツール実行ディレクトリは会話履歴に保存される

## ツール一覧

### 手動実行（`/tool`）

- `time` - システム時刻を表示
- `echo` - テキスト出力
- `grep` - ワークスペース内検索（ripgrep/grep 併用）
- `gitlog` - Git ログを表示
- `gitshow` - Git コミット詳細を表示
- `gitbranch` - Git ブランチ情報を表示
- `cmd` - ローカルコマンド実行（許可制）
- `setdir` - 作業ディレクトリを変更
- `getdir` - 現在の作業ディレクトリを確認
- `readfile` - ファイル内容を読む
- `readexcel` - Excel 範囲読取
- `readbinary` - バイナリファイル読取（Office/PDF/画像）

### LLM 自動実行（Function Calling）

LLM が会話の中で以下のツールを自動的に判断して実行します（最大3ラウンド）:

- `time` - システム時刻取得
- `grep` - ワークスペース内検索
- `gitlog` - Git ログ取得
- `gitshow` - Git コミット詳細取得
- `gitbranch` - Git ブランチ情報取得
- `readfile` - ファイル内容取得
- `readexcel` - Excel 範囲読取
- `readbinary` - バイナリファイル読取
- `writefile` - ファイル書き込み
- `localcmd` - ローカルコマンド実行

## 設定（環境変数）

### プロバイダー別設定

複数のプロバイダーに対応しており、設定されたプロバイダーの優先度は以下の通りです：
1. OpenAI（優先）
2. Gemini

#### OpenAI（優先プロバイダー）

- `MYAGENT2_API_KEY_OPENAI` **【必須】**
  - OpenAI APIキー
  - 未設定時は OpenAI は利用不可
- `MYAGENT2_BASE_URL_OPENAI`（任意）
  - OpenAI 互換エンドポイント（例: `https://api.openai.com/v1`）
  - 省略時は OpenAI 既定 URL を使用
  - OpenAI 互換 API（例: Ollama, LM Studio）を指定可能

#### Google Gemini

- `MYAGENT2_API_KEY_GEMINI` **【必須】**
  - Google Gemini APIキー
  - 未設定時は Gemini は利用不可
- `MYAGENT2_BASE_URL_GEMINI`（任意・特殊用途のみ）
  - Gemini 互換エンドポイント（ほとんどの場合は不要）
  - Gemini Native API を直接使用するため、通常は設定不要

### 共通設定

- `MYAGENT2_MODEL`（任意）
  - 新規セッション作成時のデフォルトモデル名
  - セッション作成時にドロップダウンで上書き可能
  - 例: `gpt-4o`, `gpt-4o-mini`, `gemini-pro`
- `MYAGENT2_CMD_TIMEOUT_SECONDS`（任意）
  - `localcmd` 実行タイムアウト秒数（1〜30）
  - 未設定時は 20 秒

### 設定例（PowerShell）

#### OpenAI を使用する場合

```powershell
$env:MYAGENT2_API_KEY_OPENAI="sk-..."
$env:MYAGENT2_BASE_URL_OPENAI="https://api.openai.com/v1"
$env:MYAGENT2_MODEL="gpt-4o"
$env:MYAGENT2_CMD_TIMEOUT_SECONDS="20"
```

#### Gemini を使用する場合

```powershell
$env:MYAGENT2_API_KEY_GEMINI="AIza..."
# MYAGENT2_BASE_URL_GEMINI は任意（デフォルト: https://generativelanguage.googleapis.com/v1beta）
# カスタムエンドポイント・プロキシを使用する場合のみ設定
$env:MYAGENT2_BASE_URL_GEMINI="https://your-custom-endpoint/v1beta"
$env:MYAGENT2_CMD_TIMEOUT_SECONDS="20"
```

#### 複数プロバイダーを設定した場合

```powershell
# OpenAI と Gemini の両方を設定した場合、OpenAI が優先されます
$env:MYAGENT2_API_KEY_OPENAI="sk-..."
$env:MYAGENT2_BASE_URL_OPENAI="https://api.openai.com/v1"
$env:MYAGENT2_API_KEY_GEMINI="AIza..."
# MYAGENT2_BASE_URL_GEMINI は任意（指定時のみカスタムエンドポイントを使用）
$env:MYAGENT2_BASE_URL_GEMINI="https://your-custom-endpoint/v1beta"
# セッション作成時にプロバイダー（OpenAI / Gemini）を明示的に選択可能
```
$env:MYAGENT2_MODEL="gpt-4o"
```

### 後方互換性（非推奨）

以下の環境変数は後方互換性のため保持されていますが、プロバイダー別設定の使用を推奨します：

- `MYAGENT2_API_KEY`（非推奨）→ `MYAGENT2_API_KEY_OPENAI` を使用してください
- `MYAGENT2_BASE_URL`（非推奨）→ `MYAGENT2_BASE_URL_OPENAI` を使用してください

## 配布物の作成方法

JRE 同梱の配布物を作成できます。

```bat
create-dist.bat
```

生成イメージ:

- `dist` 配下に実行用スクリプト
- `lib` 配下にアプリ本体と依存ライブラリ
- `jre` 配下に同梱ランタイム（構成に応じて）

## テスト

```bash
gradlew :app:test
```

主な確認対象:

- ローカルコマンドの許可制約
- 手動ツール実行フロー
- OpenAI 互換チャットサービスの基本動作
- 各ツールの安全制御

## プロジェクト構成

主要ディレクトリ:

- `app/src/main/java`
  - アプリ本体コード
- `app/src/test/java`
  - テストコード
- `app/src/main/resources`
  - UI リソース
- `app/build.gradle`
  - アプリ側ビルド設定
- `create-dist.bat`
  - 配布物作成スクリプト

## 安全対策・制約

ローカルコマンド実行は以下の制限があります。

- 許可コマンド限定（`git`, `grep`, `rg`, `nkf`, `ls`, `find`, `cat`, `head`, `tail`, `wc`, `stat`, `diff`, `sort`, `uniq`, `cut`, `tree`, `basename`, `dirname`, `realpath`）
- 危険なシェルメタ文字を拒否
- タイムアウト設定（既定 20 秒、最大 30 秒）
- 出力行数・出力量の上限設定
- `nkf` は `--overwrite` を含む上書き変換も利用可能


ファイル操作・grep検索は以下の方針です。

- 拡張子ホワイトリスト方式
- 相対パスを基本とした運用
- 読み取り時の文字コードフォールバック（UTF-8 優先、Shift_JIS 対応）
- `/tool grep` は次の順序で検索します。
  - `rg --encoding utf-8`
  - `rg --encoding sjis`
  - 上記の両方が 0 件の場合のみ Java 実装（Windows-31J）
  - UTF-8 と SJIS の `rg` は常に両方実行し、結果は重複排除して結合
  - `rg` 出力は逐次読み取り（ストリーミング処理）で処理し、大量ヒット時のメモリ使用量を抑制
  - 検索上限は既定 10,000 件

`readbinary` は以下の制限で動作します。

- 対応拡張子: `pdf`, `png`, `jpg`, `jpeg`, `xlsx`, `docx`, `pptx`
- サイズ上限: 10MB
- ベースディレクトリ外のパスは禁止
- テキスト抽出対応形式（`docx` / `xlsx` / `pptx` / `pdf`）は本文抽出を優先し、抽出不可時のみ base64 を返す
- 画像形式（`png` / `jpg` / `jpeg`）は base64 エンコーディングを返す

## トラブルシューティング

1. API キー未設定で LLM 応答にならない  
   - `MYAGENT2_API_KEY` を設定してください。  
   - 未設定時はスタブ応答になります。

2. ストリーミングが動作しない  
   - エンドポイントが SSE 非対応の可能性があります。  
   - 本アプリは非ストリーミング呼び出しにフォールバックします。

3. Git ツールで結果が出ない  
   - 実行ディレクトリが Git 管理下か確認してください。  
   - 必要なら `/tool setdir` で対象リポジトリへ切り替えてください。
   - セッション単位でディレクトリ指定があるため、セッション切り替えで見失うことがあります。

4. セッション作成でモデル選択ダイアログが出ない  
   - `MYAGENT2_MODEL` 環境変数が設定されていない場合、デフォルトモデルが使用されます。  
   - 複数モデルが利用可能な場合、ダイアログで選択できます。

5. Function Calling（自動ツール実行）が動作しない  
   - エンドポイントが Function Calling 非対応の可能性があります。  
   - この場合、手動ツール実行（`/tool`）は引き続き利用可能です。

6. JavaFX 関連エラーで起動できない  
   - Java 21 を使用しているか確認してください。  
   - 環境依存の JavaFX 実行条件を満たしているか確認してください。

7. ビルド失敗  
   - まず以下を実行して再試行してください。  
   - `gradlew clean`  
   - `gradlew :app:build`

## ライセンス
このプロジェクトはMITライセンスの下で公開されています。詳細はLICENSEファイルを参照してください。

