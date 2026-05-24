package jp.euks.myagent2.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import jp.euks.myagent2.chat.ChatInteractor;
import jp.euks.myagent2.chat.ChatService;

import org.junit.Test;

public class ChatInteractorCancelTest {

    static class SlowStubService implements ChatService {
        @Override
        public String replyTo(String userMessage) {
            return "";
        }

        @Override
        public void streamReplyToWithHistory(java.util.List<jp.euks.myagent2.chat.ChatMessage> history,
                String userMessage,
                Consumer<String> onToken,
                Consumer<String> onComplete,
                Consumer<Throwable> onError,
                Consumer<String> onProgress,
                BooleanSupplier isCancelled) {

            Thread t = new Thread(() -> {
                try {
                    String[] tokens = {"A","B","C"};
                    for (String tk : tokens) {
                        if (isCancelled != null && isCancelled.getAsBoolean()) {
                            // cancel: stop emitting and invoke onComplete with empty
                            onComplete.accept("");
                            return;
                        }
                        onToken.accept(tk);
                        Thread.sleep(80);
                    }
                    // finished normally
                    onComplete.accept("ABC");
                } catch (Throwable t2) {
                    onError.accept(t2);
                }
            });
            t.start();
        }
    }

    @Test
    public void cancelClearsBusyAndSuppressesTranscript() throws Exception {
        ChatService svc = new SlowStubService();
        ChatInteractor interactor = new ChatInteractor(svc);

        CountDownLatch latch = new CountDownLatch(1);

        // start streaming
        interactor.startUserMessageStream(
            "hello",
            (java.util.function.Consumer<String>) token -> {
                // ignore tokens
            },
            (java.util.function.Consumer<String>) progress -> {
            },
            (Runnable) () -> {
                latch.countDown();
            },
            (java.util.function.Consumer<Throwable>) err -> {
                latch.countDown();
            }
        );

        // give service time to start and emit at least one token
        Thread.sleep(120);

        // request cancel
        interactor.cancelCurrentRequest();

        // wait for completion (should be triggered by stub onComplete)
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue("completion should occur", completed);

        // after cancel, interactor should not be busy
        assertFalse(interactor.isBusy());

        // transcript should not include the final assistant text "ABC"
        String transcript = interactor.getTranscript();
        assertFalse("transcript must not contain final reply after cancel", transcript.contains("ABC"));
    }
}
