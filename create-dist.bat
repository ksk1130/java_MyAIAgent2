@echo off
REM === java_MyAgent2 配布用パッケージ自動生成バッチ ===
REM 必要: JDK 21 (jlink付き), JavaFX SDK 21.0.3 (lib配下)
REM 1. JDKパスとJavaFX SDKパスを指定してください
set JDK_HOME=C:\bin\jdk-21
set FX_HOME=C:\bin\jdk-21
set FX_MODULE_PATH=

REM 2. distディレクトリ初期化
rmdir /s /q dist 2>nul
mkdir dist

REM 3. 先に依存jarを解決（JavaFXモジュールの取得）
call gradlew :app:copyRuntimeLibs
if errorlevel 1 (
  echo [ERROR] 依存ライブラリの取得に失敗しました。
  exit /b 1
)

REM 4. JavaFXモジュールパスを決定
if exist "%FX_HOME%\lib\javafx-web*.jar" (
  set "FX_MODULE_PATH=%FX_HOME%\lib"
) else (
  for %%f in ("app\build\libs\runtime\javafx-web*.jar") do (
    set "FX_MODULE_PATH=app\build\libs\runtime"
    goto :fx_module_found
  )
)

:fx_module_found
if "%FX_MODULE_PATH%"=="" (
  echo [ERROR] javafx.web が見つかりません。FX_HOME または app\build\libs\runtime を確認してください。
  exit /b 1
)

REM 5. jlinkで最小JRE生成 (Windows用)
"%JDK_HOME%\bin\jlink.exe" --module-path "%JDK_HOME%\jmods;%FX_MODULE_PATH%" --add-modules java.base,java.desktop,java.logging,java.xml,java.naming,java.management,javafx.controls,javafx.graphics,javafx.base,javafx.media,javafx.web,jdk.unsupported,java.net.http,jdk.crypto.ec --output dist\jre --strip-debug --compress=zip-9 --no-header-files --no-man-pages
if errorlevel 1 (
  echo [ERROR] jlink失敗。JDK/JavaFXパス・モジュール指定を確認してください。
  exit /b 1
)

REM 6. アプリビルド＆dist配置（Gradleタスクに委譲）
call gradlew :app:createDist
if errorlevel 1 (
  echo [ERROR] Gradleビルド/コピーに失敗しました。
  exit /b 1
)

REM 7. 完了
echo [OK] dist\ 配下に配布物が生成されました。
echo 実行: dist\run.bat
