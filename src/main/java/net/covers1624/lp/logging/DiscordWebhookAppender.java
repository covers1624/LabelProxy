package net.covers1624.lp.logging;

import net.covers1624.lp.Config;
import net.covers1624.lp.util.DiscordWebhook;
import net.covers1624.quack.net.httpapi.HttpEngine;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * Created by covers1624 on 14/1/24.
 */
public class DiscordWebhookAppender extends AbstractAppender {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAX_LEN = 2000;

    private final LinkedList<String> queue = new LinkedList<>();
    private final Thread thread;
    private final Object WAIT_LOCK = new Object();

    private final HttpEngine httpEngine;
    private final Config config;

    private boolean running = true;

    public DiscordWebhookAppender(
            Layout<? extends Serializable> layout,
            HttpEngine httpEngine,
            Config config
    ) {
        super("DiscordWebhookAppender", null, layout, false, Property.EMPTY_ARRAY);
        this.httpEngine = httpEngine;
        this.config = config;

        thread = new Thread(this::runThread);
        thread.setName("DiscordWebhookAppender");
        thread.setDaemon(true);
    }

    @Override
    public void start() {
        super.start();
        thread.start();
    }

    @Override
    public void stop() {
        super.stop();
        running = false;
        synchronized (WAIT_LOCK) {
            WAIT_LOCK.notifyAll();
        }
    }

    private void runThread() {
        while (running) {
            synchronized (WAIT_LOCK) {
                try {
                    WAIT_LOCK.wait();
                } catch (InterruptedException ignored) { }
            }
            if (!running) break;

            // Quiet period.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) { }

            String toSend;
            synchronized (queue) {
                if (queue.isEmpty()) continue;
                toSend = fillBuffer(queue, MAX_LEN);
            }
            try {
                new DiscordWebhook(Objects.requireNonNull(config.discord.webhookUrl))
                        .setUsername(Objects.requireNonNull(config.discord.name))
                        .setContent(String.join("\n", toSend))
                        .execute(httpEngine);
            } catch (Throwable ex) {
                LOGGER.error("Failed to send message to discord.", ex);
            }
        }
    }

    @Override
    public void append(LogEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
            for (long l : config.discord.alert) {
                sb.append("<@").append(l).append("> ");
            }
        }
        sb.append(getLayout().toSerializable(event));
        synchronized (queue) {
            queue.add(sb.toString());
        }
        synchronized (WAIT_LOCK) {
            WAIT_LOCK.notifyAll();
        }
    }

    @VisibleForTesting
    static String fillBuffer(LinkedList<String> strings, int max) {
        StringBuilder builder = new StringBuilder(max);
        while (!strings.isEmpty()) {
            String f = strings.peek();
            if (!wouldOverflow(builder, f, max)) {
                // We can deal with this string, pop it.
                strings.pop();

                if (!builder.isEmpty()) builder.append("\n");
                builder.append(f);
            } else {
                // We would overflow the buffer by adding this string

                // We already have content in the buffer, do nothing to this new string.
                if (!builder.isEmpty()) break;

                // This single string is too long for the buffer, we need to slice it.
                strings.pop();

                builder.append(f, 0, max);
                strings.addFirst(f.substring(max));
                break;
            }
        }
        return builder.toString();
    }

    private static boolean wouldOverflow(StringBuilder dst, String src, int maxLen) {
        int len = dst.length() + src.length();
        if (!dst.isEmpty()) {
            // newline
            len++;
        }
        return len > maxLen;
    }

}
