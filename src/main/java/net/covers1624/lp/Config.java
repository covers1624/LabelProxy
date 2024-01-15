package net.covers1624.lp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.JsonAdapter;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.gson.PathTypeAdapter;
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

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private transient @Nullable Path path;

    @JsonAdapter (PathTypeAdapter.class)
    public Path logsDir = Path.of("./logs").toAbsolutePath().normalize();
    @JsonAdapter (PathTypeAdapter.class)
    public @Nullable Path tempDir = LabelProxy.RUNNING_AS_ROOT ? null : Path.of("./tmp/").toAbsolutePath().normalize();

    public Docker docker = new Docker();
    public Nginx nginx = new Nginx();
    public LetsEncrypt letsEncrypt = new LetsEncrypt();
    public List<CloudflareAuth> cloudflareAuths = new ArrayList<>();
    public Discord discord = new Discord();

    public static class Docker {

        public String socket = "/var/run/docker.sock";
        public String network = "http";
        public boolean createMissing = true;
    }

    public static class Nginx {

        public String executable = "/usr/sbin/nginx";
        @JsonAdapter (PathTypeAdapter.class)
        public Path dir = Path.of("./nginx").toAbsolutePath().normalize();
        public String user = "nginx";
        public String workers = "auto";
        public String workerConnections = "1024";
        public String logFormat = "$host $remote_addr - $remote_user [$time_local] \"$request\" $status $body_bytes_sent \"$http_referer\" \"$http_user_agent\" \"$http_x_forwarded_for\"";
    }

    public static class LetsEncrypt {

        @JsonAdapter (PathTypeAdapter.class)
        public Path dir = Path.of("./letsencrypt").toAbsolutePath().normalize();
        public int dhParamBits = 4096;
        public @Nullable String email;
        public boolean staging = false;
    }

    public static class CloudflareAuth {

        public @Nullable String serviceAuthKey;

        public @Nullable String email;
        public @Nullable String key;
    }

    public static class Discord {

        public @Nullable String webhookUrl;
        public @Nullable String name;
        public long[] alert = new long[0];
    }

    public static Config load(Path path) {
        if (Files.exists(path)) {
            try {
                Config config = JsonUtils.parse(GSON, path, Config.class);
                config.path = path;
                config.save();
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
