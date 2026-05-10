package org.example;

import java.util.Optional;

/**
 * 手動ツールコマンド（`/tool` 系）を解釈し、実行結果を返すための関数型インターフェース。
 */
@FunctionalInterface
public interface ManualToolExecutor {
    /**
     * ユーザー入力を解析し、手動ツールに該当する場合は実行結果を返す。
     * 該当しない場合は `Optional.empty()` を返す。
     *
     * @param userMessage ユーザーによる入力文字列
     * @return 実行結果の文字列または実行対象でない場合は空
     */
    Optional<String> tryExecute(String userMessage);
}