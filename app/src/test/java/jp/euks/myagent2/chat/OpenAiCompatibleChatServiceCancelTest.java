package jp.euks.myagent2.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import dev.langchain4j.service.TokenStream;
import org.junit.Test;

public class OpenAiCompatibleChatServiceCancelTest {

    @Test
    public void cancelSuppressesTokensAndCompletesEmpty() throws Exception {
        // Create real service with minimal ctor (no tools) using non-blank baseUrl/apiKey to satisfy validation
        OpenAiCompatibleChatService svc = new OpenAiCompatibleChatService("http://localhost","test","gpt-test");

        // Build a dynamic TokenStream proxy that accepts handlers and can be started
        Class<?> tsClass = Class.forName("dev.langchain4j.service.TokenStream");

        AtomicReference<Consumer<String>> partialRef = new AtomicReference<>();
        AtomicReference<Consumer<String>> completeRef = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> errorRef = new AtomicReference<>();

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("onPartialResponse")) {
                    partialRef.set((Consumer<String>) args[0]);
                    return proxy;
                }
                if (name.equals("onCompleteResponse")) {
                    completeRef.set((Consumer<String>) args[0]);
                    return proxy;
                }
                if (name.equals("onError")) {
                    errorRef.set((Consumer<Throwable>) args[0]);
                    return proxy;
                }
                if (name.equals("start")) {
                    // simulate streaming tokens asynchronously
                    new Thread(() -> {
                        try {
                            String[] toks = {"x","y","z"};
                            for (String t : toks) {
                                Consumer<String> p = partialRef.get();
                                if (p != null) p.accept(t);
                                Thread.sleep(50);
                            }
                            Consumer<String> c = completeRef.get();
                            if (c != null) c.accept("xyz");
                        } catch (Throwable ex) {
                            Consumer<Throwable> e = errorRef.get();
                            if (e != null) e.accept(ex);
                        }
                    }).start();
                    return null;
                }
                // no-op for other fluent methods
                return proxy;
            }
        };

        Object tsProxy = Proxy.newProxyInstance(
            tsClass.getClassLoader(),
            new Class[]{tsClass},
            handler
        );

        // Create a StreamingAssistant that returns our proxy
        OpenAiCompatibleChatService.StreamingAssistant streamingAssistant = new OpenAiCompatibleChatService.StreamingAssistant() {
            @Override
            public TokenStream chat(String message) {
                return (TokenStream) tsProxy;
            }
        };

        // Inject the streamingAssistant into the service via reflection
        Field f = OpenAiCompatibleChatService.class.getDeclaredField("streamingAssistant");
        f.setAccessible(true);
        f.set(svc, streamingAssistant);

        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> completedVal = new AtomicReference<>();

        BooleanSupplier isCancelled = new BooleanSupplier() {
            volatile boolean flag = false;
            @Override
            public boolean getAsBoolean() {
                return flag;
            }
            public void cancel() { flag = true; }
        };

        // start streaming
        svc.streamReplyToWithHistory(
            List.of(),
            "hi",
            token -> received.add(token),
            full -> {
                completedVal.set(full);
                latch.countDown();
            },
            err -> {
                latch.countDown();
            },
            progress -> {},
            isCancelled
        );

        // allow a very short time then request cancel to suppress most tokens
        Thread.sleep(10);
        // now request cancel by reflection (toggle flag)
        Method mFlag = isCancelled.getClass().getMethod("cancel");
        mFlag.invoke(isCancelled);

        boolean ok = latch.await(2, TimeUnit.SECONDS);
        assertTrue(ok);
        // after cancel, completed value should be empty string per implementation
        assertEquals("", completedVal.get());
        // tokens received should be at most 1 (cancel suppressed subsequent tokens)
        assertTrue(received.size() <= 1);
    }
}
