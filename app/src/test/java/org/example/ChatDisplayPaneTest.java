package org.example;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class ChatDisplayPaneTest {

    @Test
    public void testTurnExtractionSimple() {
        String transcript = "You: Hello\nHow are you?\nAssistant: I'm fine\nThanks\nYou: Another\nLine2";
        List<String> turns = ChatDisplayPane.parseTurns(transcript);
        assertEquals(3, turns.size());
        assertTrue(turns.get(0).startsWith("You: Hello"));
        assertTrue(turns.get(1).startsWith("Assistant: I'm fine"));
        assertTrue(turns.get(2).startsWith("You: Another"));
    }
}
