# java_MyAgent2

JavaFX で構築したデスクトップ向け AI チャットエージェントです。  
OpenAI 互換エンドポイントに接続し、ツール呼び出し（検索・Git参照・ファイルI/O・ローカルコマンド）を安全制約付きで実行できます。  
会話履歴の保存、システムプロンプト編集、ストリーミング表示にも対応しています。

## 目次

- [java\_MyAgent2](#java_myagent2)
  - [目次](#目次)
  - [プロジェクト概要](#プロジェクト概要)
  - [主な機能](#主な機能)
  - [技術スタック](#技術スタック)
  - [動作環境](#動作環境)
  - [セットアップ](#セットアップ)
  - [起動方法](#起動方法)
  - [使い方](#使い方)
  - [ツール一覧](#ツール一覧)
  - [設定（環境変数）](#設定環境変数)
  - [配布物の作成方法](#配布物の作成方法)
  - [テスト](#テスト)
  - [プロジェクト構成](#プロジェクト構成)
  - [安全対策・制約](#安全対策制約)
  - [トラブルシューティング](#トラブルシューティング)
  - [ライセンス](#ライセンス)
    - [サードパーティライセンス](#サードパーティライセンス)
      - [RichTextFX](#richtextfx)

## プロジェクト概要

java_MyAgent2 は、ローカル開発支援を目的としたデスクトップ AI エージェントです。  
特徴は以下です。

- JavaFX ベースの軽量 GUI
- OpenAI 互換 API への接続
- ツール実行を伴う回答生成（Function Calling）
- 会話履歴の永続化（JSON）
- ストリーミング応答表示（非対応エンドポイント時は非ストリーミングへフォールバック）

## 主な機能

- チャット UI
  - 複数行入力
  - Ctrl+Enter で送信
  - Shift+Enter で改行
- 応答生成
  - 非同期処理（UI ブロック回避）
  - ストリーミング逐次表示
  - ストリーミング失敗時の同期フォールバック
- ツール実行
  - 手動実行（`/tool` コマンド）
  - LLM 自動実行（Function Calling）
- 開発支援
  - ワークスペース内検索
  - Git 履歴参照
  - ファイル読み書き
  - ローカルコマンド実行（許可制）
- 運用機能
  - 会話履歴の保存・再読込
  - システムプロンプトの編集
  - ツール作業ディレクトリの切り替え

## 技術スタック

- 言語: Java 21
- ビルド: Gradle
- UI: JavaFX 21.0.3
- LLM 連携: LangChain4j 1.12.2
- JSON: Gson
- テスト: JUnit 4
- テキストエリア装飾: RichTextFX

## 動作環境

- OS: Windows を主対象（他 OS でも Java 実行環境があれば動作可能）
- JDK: 21
- ネットワーク: OpenAI 互換 API に到達可能
- 任意: Git コマンド利用時は Git が PATH 上に存在

補足:

- Windows ARM64 環境では JavaFX プラグイン制約を回避する構成を採用しています。

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

## 使い方

基本操作:

- メッセージ入力後に送信
- 応答中は逐次的にテキストが表示
- 完了時に整形済み表示へ更新

手動ツール実行:

- 先頭に `/tool` を付けて実行
- 例:
  - `/tool time`
  - `/tool grep キーワード`
  - `/tool gitlog App.java`
  - `/tool readfile 相対パス`
  - `/tool setdir ディレクトリパス`

ディレクトリ切替:

- `/tool setdir` で作業基点を変更
- `/tool getdir` で現在値を確認

## ツール一覧

手動実行（`/tool`）:

- `time`
- `echo`
- `grep`
- `gitlog`
- `gitshow`
- `gitbranch`
- `cmd`
- `setdir`
- `getdir`
- `readfile`

LLM 自動実行（Function Calling）:

- `time`
- `grep`
- `gitlog`
- `gitshow`
- `gitbranch`
- `readfile`
- `writefile`
- `localcmd`

## 設定（環境変数）

- `MYAGENT2_API_KEY`
  - OpenAI 互換 API 利用時に必須
  - 未設定時はスタブ応答サービスにフォールバック
- `MYAGENT2_BASE_URL`
  - 省略時は OpenAI 既定 URL
- `MYAGENT2_MODEL`
  - 省略時は既定モデル名を使用

例（PowerShell）:

```powershell
$env:MYAGENT2_API_KEY="your_api_key"
$env:MYAGENT2_BASE_URL="https://your-endpoint/v1"
$env:MYAGENT2_MODEL="gpt-4o-mini"
```

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

- 許可コマンド限定（`git` / `grep` / `rg`）
- 危険なシェルメタ文字を拒否
- タイムアウト設定（固定秒数）
- 出力行数・出力量の上限設定


ファイル操作・grep検索は以下の方針です。

- 拡張子ホワイトリスト方式
- 相対パスを基本とした運用
- 読み取り時の文字コードフォールバック（UTF-8 優先、Shift_JIS 対応）
- `/tool grep` は各ファイルを「UTF-8→Shift_JIS（CP932）」の順で自動判定し、どちらかで正しく読めた場合のみ検索対象とします。UTF-8/SJIS混在プロジェクトでも横断検索できます。

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

4. JavaFX 関連エラーで起動できない  
   - Java 21 を使用しているか確認してください。  
   - 環境依存の JavaFX 実行条件を満たしているか確認してください。

5. ビルド失敗  
   - まず以下を実行して再試行してください。  
   - `gradlew clean`  
   - `gradlew :app:build`

## ライセンス
このプロジェクトはMITライセンスの下で公開されています。詳細はLICENSEファイルを参照してください。

### サードパーティライセンス
#### RichTextFX
https://github.com/FXMisc/RichTextFX/
```
Copyright (c) 2013-2023, Tomas Mikula and contributors
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
