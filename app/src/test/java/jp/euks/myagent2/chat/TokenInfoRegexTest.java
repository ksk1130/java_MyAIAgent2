package jp.euks.myagent2.chat;

import org.junit.Test;
import static org.junit.Assert.*;

public class TokenInfoRegexTest {

    /**
     * トークン情報行の正規表現パターンテスト
     */
    @Test
    public void testTokenInfoRegexPatterns() {
        String pattern = "^\\s*●トークン:\\s*input=.*output=.*計.*";
        
        // テストケース：成功するべき
        String[] successCases = {
            "●トークン: input=2,566 output=15 (計2,581)",
            "●トークン: input=100 output=50 (計150)",
            "  ●トークン: input=1000 output=1000 (計2000)",
            "●トークン:input=10output=5計15",
        };
        
        for (String line : successCases) {
            boolean matches = line.matches(pattern);
            System.out.println("Pattern test: '" + line + "' -> " + matches);
            assertTrue("Should match: '" + line + "'", matches);
        }
        
        // テストケース：失敗するべき
        String[] failCases = {
            "トークン情報はありません",
            "input=100 output=50",
            "●トークン: ",
        };
        
        for (String line : failCases) {
            boolean matches = line.matches(pattern);
            System.out.println("Pattern test (should fail): '" + line + "' -> " + matches);
            assertFalse("Should NOT match: '" + line + "'", matches);
        }
    }
}
