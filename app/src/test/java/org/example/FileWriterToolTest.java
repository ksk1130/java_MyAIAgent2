package org.example;

import org.example.tools.FileWriterTool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * FileWriterTool のユニットテスト。
 */
public class FileWriterToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FileWriterTool tool() {
        return new FileWriterTool(tmp.getRoot().toPath());
    }

    @Test
    public void writeFile_正常にファイルを作成して内容を書き込む() throws IOException {
        String result = tool().writeFile("result.txt", "hello world");
        assertTrue("成功メッセージが返る", result.contains("result.txt"));
        Path written = tmp.getRoot().toPath().resolve("result.txt");
        assertTrue("ファイルが存在する", Files.exists(written));
        assertEquals("hello world", Files.readString(written));
    }

    @Test
    public void writeFile_サブディレクトリを自動作成する() throws IOException {
        String result = tool().writeFile("output/sub/result.txt", "data");
        assertFalse("エラーなし", result.startsWith("(error)"));
        assertTrue(Files.exists(tmp.getRoot().toPath().resolve("output/sub/result.txt")));
    }

    @Test
    public void writeFile_不正な拡張子はエラーを返す() {
        String result = tool().writeFile("test.java", "public class T {}");
        assertTrue("不正拡張子エラー", result.startsWith("(error)"));
    }

    @Test
    public void writeFile_パストラバーサルを拒否する() {
        String result = tool().writeFile("../outside.txt", "malicious");
        assertTrue("パストラバーサル拒否", result.startsWith("(error)"));
    }

    @Test
    public void writeFile_空パスはエラー() {
        String result = tool().writeFile("", "content");
        assertTrue(result.startsWith("(error)"));
    }

    @Test
    public void writeFile_nullパスはエラー() {
        String result = tool().writeFile(null, "content");
        assertTrue(result.startsWith("(error)"));
    }

    @Test
    public void writeFile_コンテンツが長すぎるとエラー() {
        String longContent = "x".repeat(50_001);
        String result = tool().writeFile("output.txt", longContent);
        assertTrue("長すぎるコンテンツエラー", result.startsWith("(error)"));
    }

    @Test
    public void writeFile_md拡張子は許可される() {
        String result = tool().writeFile("notes.md", "# title");
        assertFalse(result.startsWith("(error)"));
    }

    @Test
    public void writeFile_csv拡張子は許可される() throws IOException {
        String result = tool().writeFile("data.csv", "a,b,c");
        assertFalse(result.startsWith("(error)"));
        assertEquals("a,b,c", Files.readString(tmp.getRoot().toPath().resolve("data.csv")));
    }

    @Test
    public void writeFile_ps1拡張子は許可される() throws IOException {
        String result = tool().writeFile("script.ps1", "Write-Output 'hello'");
        assertFalse("ps1 は許可拡張子", result.startsWith("(error)"));
        assertEquals("Write-Output 'hello'", Files.readString(tmp.getRoot().toPath().resolve("script.ps1")));
    }

    @Test
    public void writeFile_改行がリテラルの場合は改行に正規化する() throws IOException {
        String result = tool().writeFile("summary.txt", "line1\\nline2\\nline3");
        assertFalse(result.startsWith("(error)"));
        String actual = Files.readString(tmp.getRoot().toPath().resolve("summary.txt"));
        assertEquals("line1\nline2\nline3", actual);
    }

    @Test
    public void writeFile_実改行が含まれる場合はそのまま保持する() throws IOException {
        String input = "line1\nline2";
        String result = tool().writeFile("already_newline.txt", input);
        assertFalse(result.startsWith("(error)"));
        String actual = Files.readString(tmp.getRoot().toPath().resolve("already_newline.txt"));
        assertEquals(input, actual);
    }
}
