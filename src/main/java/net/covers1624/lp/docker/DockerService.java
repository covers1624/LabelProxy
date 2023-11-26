package net.covers1624.lp.docker;

import com.google.gson.Gson;
import net.covers1624.lp.Config;
import net.covers1624.lp.docker.data.ContainerSummary;
import net.covers1624.lp.docker.data.DockerContainer;
import net.covers1624.lp.docker.data.DockerNetwork;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.net.httpapi.WebBody;
import net.covers1624.quack.net.httpapi.curl4j.Curl4jEngineRequest;
import net.covers1624.quack.net.httpapi.curl4j.Curl4jEngineResponse;
import net.covers1624.quack.net.httpapi.curl4j.Curl4jHttpEngine;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Created by covers1624 on 1/11/23.
 */
public class DockerService {

    private static final Gson GSON = new Gson();

    private final Config config;
    private final Curl4jHttpEngine httpEngine;

    public DockerService(Config config, Curl4jHttpEngine httpEngine) {
        this.config = config;
        this.httpEngine = httpEngine;
    }

    public @Nullable DockerNetwork inspectNetwork(String name) {
        Curl4jEngineRequest request = httpEngine.newRequest()
                .method("GET", null)
                .unixSocket(config.dockerSocket)
                .url("http://v1.25/networks/" + name);
        try (Curl4jEngineResponse resp = request.execute()) {
            if (resp.statusCode() == 404) return null;

            WebBody body = resp.body();
            assert body != null;

            return JsonUtils.parse(GSON, body.open(), DockerNetwork.class);
        } catch (IOException ex) {
            throw new RuntimeException("Docker command failed.", ex);
        }
    }

    public DockerNetwork createNetwork(String name) {
        Curl4jEngineRequest request = httpEngine.newRequest()
                .method("POST", jsonBody(Map.of(
                        "Name", name,
                        "CheckDuplicates", true,
                        "Driver", "bridge"
                )))
                .unixSocket(config.dockerSocket)
                .url("http://v1.25/networks/create");
        try (Curl4jEngineResponse resp = request.execute()) {
            if (resp.statusCode() != 201) throw new IllegalStateException("Expected 201 response. Got: " + resp.statusCode());

            WebBody body = resp.body();
            assert body != null;

            record Response(String Id) { }
            Response r = JsonUtils.parse(GSON, body.open(), Response.class);

            return Objects.requireNonNull(inspectNetwork(r.Id), "Inspect after create did not return a network?");
        } catch (IOException ex) {
            throw new RuntimeException("Docker command failed.", ex);
        }
    }

    public List<ContainerSummary> listContainers() {
        Curl4jEngineRequest request = httpEngine.newRequest()
                .method("GET", null)
                .unixSocket(config.dockerSocket)
                .url("http://v1.25/containers/json");
        try (Curl4jEngineResponse resp = request.execute()) {
            if (resp.statusCode() != 200)
                throw new IllegalStateException("Expected 200 response. Got: " + resp.statusCode());

            WebBody body = resp.body();
            assert body != null;

            return JsonUtils.parse(GSON, body.open(), ContainerSummary.CONTAINER_LIST);
        } catch (IOException ex) {
            throw new RuntimeException("Docker command failed.", ex);
        }
    }

    public DockerContainer inspectContainer(String id) {
        Curl4jEngineRequest request = httpEngine.newRequest()
                .method("GET", null)
                .unixSocket(config.dockerSocket)
                .url("http://v1.25/containers/" + id + "/json");
        try (Curl4jEngineResponse resp = request.execute()) {
            if (resp.statusCode() != 200) throw new IllegalStateException("Expected 200 response. Got: " + resp.statusCode());

            WebBody body = resp.body();
            assert body != null;

            return JsonUtils.parse(GSON, body.open(), DockerContainer.class);
        } catch (IOException ex) {
            throw new RuntimeException("Docker command failed.", ex);
        }
    }

    public DockerContainer connectNetwork(String network, String container) {
        Curl4jEngineRequest request = httpEngine.newRequest()
                .method("POST", jsonBody(Map.of(
                        "Container", container
                )))
                .unixSocket(config.dockerSocket)
                .url("http://v1.25/networks/" + network + "/connect");
        try (Curl4jEngineResponse resp = request.execute()) {
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Expected 201 response. Got: " + resp.statusCode());
            }
            return inspectContainer(container);
        } catch (IOException ex) {
            throw new RuntimeException("Docker command failed.", ex);
        }
    }

    private static WebBody jsonBody(Object obj) {
        return WebBody.string(
                GSON.toJson(obj),
                "application/json"
        );
    }
}
