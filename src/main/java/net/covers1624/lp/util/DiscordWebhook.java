package net.covers1624.lp.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.covers1624.quack.net.httpapi.EngineRequest;
import net.covers1624.quack.net.httpapi.EngineResponse;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.covers1624.quack.net.httpapi.WebBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Based off the implementation provided here <a href="https://gist.github.com/k3kdude/fba6f6b37594eae3d6f9475330733bdb">...</a>
 * <p>
 * Heavily modified and cleaned up.
 * <p>
 * Class used to execute Discord Webhooks with low effort
 */
public class DiscordWebhook {

    private static final Logger LOGGER = LogManager.getLogger();

    private final String url;
    @Nullable
    private String content;
    @Nullable
    private String username;
    @Nullable
    private String avatarUrl;
    private boolean tts;
    private final List<Embed> embeds = new ArrayList<>();

    /**
     * Constructs a new DiscordWebhook instance
     *
     * @param url The webhook URL obtained in Discord
     */
    public DiscordWebhook(String url) {
        this.url = url;
    }

    public DiscordWebhook setContent(String content) {
        this.content = content;
        return this;
    }

    public DiscordWebhook setUsername(String username) {
        this.username = username;
        return this;
    }

    public DiscordWebhook setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
        return this;
    }

    public DiscordWebhook setTts(boolean tts) {
        this.tts = tts;
        return this;
    }

    public DiscordWebhook addEmbed(Embed embed) {
        embeds.add(embed);
        return this;
    }

    public void execute(HttpEngine engine) throws IOException {
        if (content == null && embeds.isEmpty()) {
            throw new IllegalArgumentException("Set content or add at least one EmbedObject");
        }

        JsonObject json = new JsonObject();

        json.addProperty("content", content);
        json.addProperty("username", username);
        json.addProperty("avatar_url", avatarUrl);
        json.addProperty("tts", tts);

        if (!embeds.isEmpty()) {
            JsonArray embedObjects = new JsonArray();

            for (Embed embed : embeds) {
                JsonObject jsonEmbed = new JsonObject();

                jsonEmbed.addProperty("title", embed.getTitle());
                jsonEmbed.addProperty("description", embed.getDescription());
                jsonEmbed.addProperty("url", embed.getUrl());

                if (embed.getColor() != null) {
                    Color color = embed.getColor();
                    int rgb = color.getRed();
                    rgb = (rgb << 8) + color.getGreen();
                    rgb = (rgb << 8) + color.getBlue();

                    jsonEmbed.addProperty("color", rgb);
                }

                Embed.Footer footer = embed.getFooter();
                Embed.Image image = embed.getImage();
                Embed.Thumbnail thumbnail = embed.getThumbnail();
                Embed.Author author = embed.getAuthor();
                List<Embed.Field> fields = embed.getFields();

                if (footer != null) {
                    JsonObject jsonFooter = new JsonObject();

                    jsonFooter.addProperty("text", footer.text());
                    jsonFooter.addProperty("icon_url", footer.iconUrl());
                    jsonEmbed.add("footer", jsonFooter);
                }

                if (image != null) {
                    JsonObject jsonImage = new JsonObject();

                    jsonImage.addProperty("url", image.url());
                    jsonEmbed.add("image", jsonImage);
                }

                if (thumbnail != null) {
                    JsonObject jsonThumbnail = new JsonObject();

                    jsonThumbnail.addProperty("url", thumbnail.url());
                    jsonEmbed.add("thumbnail", jsonThumbnail);
                }

                if (author != null) {
                    JsonObject jsonAuthor = new JsonObject();

                    jsonAuthor.addProperty("name", author.name());
                    jsonAuthor.addProperty("url", author.url());
                    jsonAuthor.addProperty("icon_url", author.iconUrl());
                    jsonEmbed.add("author", jsonAuthor);
                }

                JsonArray jsonFields = new JsonArray();
                for (Embed.Field field : fields) {
                    JsonObject jsonField = new JsonObject();

                    jsonField.addProperty("name", field.name());
                    jsonField.addProperty("value", field.value());
                    jsonField.addProperty("inline", field.inline());

                    jsonFields.add(jsonField);
                }

                jsonEmbed.add("fields", jsonFields);
                embedObjects.add(jsonEmbed);
            }

            json.add("embeds", embedObjects);
        }

        EngineRequest request = engine.newRequest()
                .method("POST", WebBody.string(json.toString(), "application/json"))
                .url(url);

        try (EngineResponse response = request.execute()) {
            if (response.statusCode() != 204) {
                WebBody body = response.body();
                LOGGER.error("Failed to send post request to discord. Got: {} {}", response.statusCode(), body != null ? body.asString() : "no body??");
            }
        }
    }

    public static class Embed {

        @Nullable
        private String title;
        @Nullable
        private String description;
        @Nullable
        private String url;
        @Nullable
        private Color color;

        @Nullable
        private Footer footer;
        @Nullable
        private Thumbnail thumbnail;
        @Nullable
        private Image image;
        @Nullable
        private Author author;
        private final List<Field> fields = new ArrayList<>();

        @Nullable
        public String getTitle() {
            return title;
        }

        @Nullable
        public String getDescription() {
            return description;
        }

        @Nullable
        public String getUrl() {
            return url;
        }

        @Nullable
        public Color getColor() {
            return color;
        }

        @Nullable
        public Footer getFooter() {
            return footer;
        }

        @Nullable
        public Thumbnail getThumbnail() {
            return thumbnail;
        }

        @Nullable
        public Image getImage() {
            return image;
        }

        @Nullable
        public Author getAuthor() {
            return author;
        }

        public List<Field> getFields() {
            return fields;
        }

        public Embed setTitle(String title) {
            this.title = title;
            return this;
        }

        public Embed setDescription(String description) {
            this.description = description;
            return this;
        }

        public Embed setUrl(String url) {
            this.url = url;
            return this;
        }

        public Embed setColor(Color color) {
            this.color = color;
            return this;
        }

        public Embed setFooter(String text, String icon) {
            footer = new Footer(text, icon);
            return this;
        }

        public Embed setThumbnail(String url) {
            thumbnail = new Thumbnail(url);
            return this;
        }

        public Embed setImage(String url) {
            image = new Image(url);
            return this;
        }

        public Embed setAuthor(String name, String url, String icon) {
            author = new Author(name, url, icon);
            return this;
        }

        public Embed addField(String name, String value, boolean inline) {
            fields.add(new Field(name, value, inline));
            return this;
        }

        public record Footer(String text, String iconUrl) { }

        public record Thumbnail(String url) { }

        public record Image(String url) { }

        public record Author(String name, String url, String iconUrl) { }

        public record Field(String name, String value, boolean inline) { }
    }
}
