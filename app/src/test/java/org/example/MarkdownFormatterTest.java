package org.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import javafx.scene.text.Text;
import org.junit.Test;

public class MarkdownFormatterTest {

    @Test
    public void testBoldParsing() {
        List<Text> nodes = MarkdownFormatter.parseMarkdownLine("This is **bold** text");
        StringBuilder sb = new StringBuilder();
        for (Text t : nodes) sb.append(t.getText());
        assertEquals("This is bold text", sb.toString());
    }

    @Test
    public void testItalicParsing() {
        List<Text> nodes = MarkdownFormatter.parseMarkdownLine("plain *italic* end");
        StringBuilder sb = new StringBuilder();
        for (Text t : nodes) sb.append(t.getText());
        assertEquals("plain italic end", sb.toString());
    }

    @Test
    public void testInlineCode() {
        List<Text> nodes = MarkdownFormatter.parseMarkdownLine("use `code` here");
        StringBuilder sb = new StringBuilder();
        for (Text t : nodes) sb.append(t.getText());
        assertEquals("use code here", sb.toString());
    }

    @Test
    public void testHeading() {
        List<Text> nodes = MarkdownFormatter.parseMarkdownLine("# Heading");
        assertTrue(nodes.size() == 1);
        assertEquals("Heading\n", nodes.get(0).getText());
    }
}
