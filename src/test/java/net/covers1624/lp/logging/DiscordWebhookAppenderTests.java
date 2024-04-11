package net.covers1624.lp.logging;

import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Created by covers1624 on 11/4/24.
 */
public class DiscordWebhookAppenderTests {

    @Test
    public void testFillBufferNoWork() {
        LinkedList<String> lines = new LinkedList<>(List.of("a", "b", "c"));
        assertEquals("a\nb\nc", DiscordWebhookAppender.fillBuffer(lines, 20));
    }

    @Test
    public void testFillBufferExactFill() {
        LinkedList<String> lines = new LinkedList<>(List.of("a", "b", "c", "d"));
        assertEquals("a\nb", DiscordWebhookAppender.fillBuffer(lines, 3));
        assertEquals("c\nd", DiscordWebhookAppender.fillBuffer(lines, 3));
    }

    @Test
    public void testFillBufferUnevenFill() {
        LinkedList<String> lines = new LinkedList<>(List.of("a", "b", "c", "de"));
        assertEquals("a\nb", DiscordWebhookAppender.fillBuffer(lines, 3));
        assertEquals("c", DiscordWebhookAppender.fillBuffer(lines, 3));
        assertEquals("de", DiscordWebhookAppender.fillBuffer(lines, 3));
    }

    @Test
    public void testFillBufferSlicing() {
        LinkedList<String> lines = new LinkedList<>(List.of("a", "b", "c", "defg"));
        assertEquals("a\nb", DiscordWebhookAppender.fillBuffer(lines, 3));
        assertEquals("c", DiscordWebhookAppender.fillBuffer(lines, 3));
        assertEquals("def", DiscordWebhookAppender.fillBuffer(lines, 3));
        assertEquals("g", DiscordWebhookAppender.fillBuffer(lines, 3));
    }
}
