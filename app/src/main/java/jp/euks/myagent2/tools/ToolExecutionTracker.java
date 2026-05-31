package jp.euks.myagent2.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM の tool calling で実行された tool の呼び出し記録を保持する。
 * tool メソッド側で呼び出しを記録し、UI 側で表示する目的で使用される。
 */
public class ToolExecutionTracker {
    private final List<ToolExecution> executions = new ArrayList<>();

    /**
     * tool 呼び出しを記録する。
     * 
     * @param toolName  tool の名前（例: "localcmd", "grep"）
     * @param parameter tool に渡された引数（例: "git log -5"）
     * @param result    tool の実行結果
     */
    public void record(String toolName, String parameter, String result) {
        executions.add(new ToolExecution(toolName, parameter, result));
    }

    /**
     * 記録されたすべての tool 実行結果を取得する。
     * 
     * @return tool 実行の一覧
     */
    public List<ToolExecution> getExecutions() {
        return new ArrayList<>(executions);
    }

    /**
     * 記録をクリアする。
     */
    public void clear() {
        executions.clear();
    }

    /**
     * ToolExecution の表示用フォーマットを返します。
     *
     * @return フォーマット済みの表示文字列
     */
    public record ToolExecution(String toolName, String parameter, String result) {
        /**
         * 表示用のフォーマット文字列を返す。
         * 例: "📌 localcmd: git log -5\n$ git log -5\n(tool:cmd)\n..."
         */
        public String format() {
            return ">> %s: ".formatted(toolName) + parameter + "\n" + result;
        }
    }
}

