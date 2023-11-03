package net.covers1624.lp;

import com.google.gson.Gson;
import net.covers1624.quack.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by covers1624 on 1/11/23.
 */
public class Config {

    private static final Gson GSON = new Gson();

    private transient @Nullable Path path;

    public String dockerSocket = "/var/run/docker.sock";
    public String networkName = "http";
    public boolean createMissingNetwork = true;

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
