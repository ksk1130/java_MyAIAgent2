package jp.euks.myagent2.tools;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Test;

public class GitLogToolTest {
    private Path gitRepoDir;

    @Before
    public void setUpGitRepo() throws Exception {
        gitRepoDir = Files.createTempDirectory("git-log-test");
        runGit(gitRepoDir, "git", "init", "-b", "main");
        runGit(gitRepoDir, "git", "config", "user.email", "test@example.com");
        runGit(gitRepoDir, "git", "config", "user.name", "Test User");

        Path readme = gitRepoDir.resolve("README.md");
        Files.writeString(readme, "# test\n", StandardCharsets.UTF_8);
        runGit(gitRepoDir, "git", "add", ".");
        runGit(gitRepoDir, "git", "commit", "-m", "initial commit");
    }

    @Test
    public void logReturnsRecentCommitLine() {
        GitLogTool tool = new GitLogTool(gitRepoDir, 5);
        String result = tool.log("");

        assertTrue(result, result.startsWith("(tool:gitlog)"));
        assertTrue(result, result.contains("initial commit"));
    }

    @Test
    public void logFiltersToSpecificFile() {
        GitLogTool tool = new GitLogTool(gitRepoDir, 5);
        String result = tool.log("README.md");

        assertTrue(result, result.startsWith("(tool:gitlog)"));
        assertTrue(result, result.contains("initial commit"));
    }

    @Test
    public void showReturnsCommitInfo() {
        GitLogTool tool = new GitLogTool(gitRepoDir, 5);
        String result = tool.show("HEAD");

        assertTrue(result, result.startsWith("(tool:gitshow)"));
        assertTrue(result, result.contains("initial commit"));
    }

    @Test
    public void showReturnsErrorWhenArgsEmpty() {
        GitLogTool tool = new GitLogTool(gitRepoDir, 5);
        String result = tool.show("");

        assertTrue(result, result.startsWith("(tool:error)"));
    }

    @Test
    public void logReturnsErrorForUnsafeArg() {
        GitLogTool tool = new GitLogTool(gitRepoDir, 5);
        String result = tool.log("--all");

        assertTrue(result, result.startsWith("(tool:error)"));
    }

    @Test
    public void showReturnsErrorForUnsafeArg() {
        GitLogTool tool = new GitLogTool(gitRepoDir, 5);
        String result = tool.show("--all");

        assertTrue(result, result.startsWith("(tool:error)"));
    }

    private static void runGit(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
        p.waitFor();
    }
}
