package jp.euks.myagent2.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.Test;

public class DefaultManualToolExecutorTest {
    @Test
    public void tryExecuteReturnsEmptyForNormalMessage() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        Optional<String> result = executor.tryExecute("hello");

        assertFalse(result.isPresent());
    }

    @Test
    public void tryExecuteRunsTimeTool() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-05-02T10:30:40Z"), ZoneId.of("UTC"));
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor(fixedClock);

        String result = executor.tryExecute("/tool time").orElse("");

        assertEquals("(tool:time) 2026-05-02 10:30:40", result);
    }

    @Test
    public void tryExecuteRunsEchoTool() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool echo hi").orElse("");

        assertEquals("(tool:echo) hi", result);
    }

    @Test
    public void tryExecuteReturnsHelpText() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool help").orElse("");

        assertTrue(result.startsWith("(tool:help)"));
    }

    @Test
    public void tryExecuteRunsGrepTool() throws Exception {
        Path tempDir = Files.createTempDirectory("grep-test");
        Path testFile = tempDir.resolve("notes.txt");
        Files.writeString(testFile, "alpha\nbeta match\ngamma\n", StandardCharsets.UTF_8);

        DefaultManualToolExecutor executor = new DefaultManualToolExecutor(
            Clock.systemUTC(),
            new WorkspaceGrepTool(tempDir, 10));

        String result = executor.tryExecute("/tool grep beta").orElse("");

        assertTrue(result.startsWith("(tool:grep)"));
        assertTrue(result.contains("notes.txt:2 | beta match"));
    }

    @Test
    public void tryExecuteRunsCmdToolForGitStatus() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool cmd git status").orElse("");

        assertTrue(result.contains("$ git status") && (result.contains("(tool:cmd)") || result.contains("(error)") 
            || result.contains("fatal:")));
    }

    @Test
    public void tryExecuteAllowsCmdWithPipe() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool cmd git log | grep").orElse("");

        assertFalse(result, result.contains("危険な記号"));
    }

    @Test
    public void tryExecuteRejectsCmdWithUnpermittedCommand() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool cmd java -version").orElse("");

        assertTrue(result, result.contains("許可されていないコマンドです"));
    }
}