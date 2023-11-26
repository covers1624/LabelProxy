package net.covers1624.lp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.JsonAdapter;
import net.covers1624.quack.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 1/11/23.
 */
public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient @Nullable Path path;

    public String dockerSocket = "/var/run/docker.sock";
    public String networkName = "http";
    public boolean createMissingNetwork = true;

    public List<CloudflareAuth> cloudflareAuths = new ArrayList<>();

    public static class CloudflareAuth {

        public @Nullable String serviceAuthKey;

        public @Nullable String email;
        public @Nullable String key;
    }

    public static Config load(Path path) {
        if (Files.exists(path)) {
            try {
                Config config = JsonUtils.parse(GSON, path, Config.class);
                config.path = path;
                return config;
            } catch (IOException ex) {
                throw new RuntimeException("Failed to load config", ex);
            }
        }
        Config config = new Config();
        config.path = path;
        config.save();
        return config;
    }

    public void save() {
        try {
            assert path != null;
            JsonUtils.write(GSON, path, this);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to save config.", ex);
        }
    }
}
