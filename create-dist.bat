@echo off
REM === java_MyAgent2 配布用パッケージ自動生成バッチ ===
REM 必要: JDK 21 (jlink付き), JavaFX SDK 21.0.3 (lib配下)
REM 1. JDKパスとJavaFX SDKパスを指定してください
set JDK_HOME=C:\bin\jdk-21
set FX_HOME=C:\bin\jdk-21

REM 2. distディレクトリ初期化
rmdir /s /q dist 2>nul
mkdir dist

REM 3. jlinkで最小JRE生成 (Windows用)
"%JDK_HOME%\bin\jlink.exe" --module-path "%JDK_HOME%\jmods;%FX_HOME%\lib" --add-modules java.base,java.desktop,java.logging,java.xml,javafx.controls,javafx.graphics,javafx.base,jdk.unsupported,java.net.http,jdk.crypto.ec --output dist\jre --strip-debug --compress=zip-9 --no-header-files --no-man-pages
if errorlevel 1 (
  echo [ERROR] jlink失敗。JDK/JavaFXパス・モジュール指定を確認してください。
  exit /b 1
)


REM 4. アプリビルド＆dist配置（Gradleタスクに委譲）
call gradlew :app:createDist
if errorlevel 1 (
  echo [ERROR] Gradleビルド/コピーに失敗しました。
  exit /b 1
)

REM 5. 完了
echo [OK] dist\ 配下に配布物が生成されました。
echo 実行: dist\run.bat
