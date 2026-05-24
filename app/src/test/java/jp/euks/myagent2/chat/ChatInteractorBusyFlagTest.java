package jp.euks.myagent2.chat;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jp.euks.myagent2.tools.DefaultManualToolExecutor;

public class ChatInteractorBusyFlagTest {

    static class DelayedChatService implements ChatService {
        @Override
        public String replyTo(String userMessage) { return "ok"; }
        @Override
        public void streamReplyToWithHistory(java.util.List<ChatMessage> history, String userMessage,
                                            Consumer<String> onToken,
                                            Consumer<String> onComplete,
                                            Consumer<Throwable> onError,
                                            Consumer<String> onProgress) {
            new Thread(() -> {
                try {
                    onToken.accept("partial1");
                    Thread.sleep(120);
                    onToken.accept("partial2");
                    Thread.sleep(120);
                    onComplete.accept("final-text");
                } catch (Throwable t) {
                    onError.accept(t);
                }
            }).start();
        }
    }

    @Test
    public void busyFlagIsSetDuringStreaming() throws Exception {
        DelayedChatService svc = new DelayedChatService();
        ChatInteractor interactor = new ChatInteractor(svc, new DefaultManualToolExecutor(), null, "s-busy");

        CountDownLatch done = new CountDownLatch(1);
        interactor.startUserMessageStream(
            "hello",
            token -> {},
            progress -> {},
            () -> done.countDown(),
            err -> fail("stream error: " + err)
        );

        // 少し待ってから busy フラグを確認
        Thread.sleep(30);
        assertTrue("Interactor should be busy during streaming", interactor.isBusy());

        boolean completed = done.await(2, TimeUnit.SECONDS);
        assertTrue("stream should complete", completed);
        assertFalse("Interactor should be not busy after complete", interactor.isBusy());
        String tr = interactor.getTranscript();
        assertTrue(tr.contains("final-text") || tr.contains("partial2") );
    }
}
