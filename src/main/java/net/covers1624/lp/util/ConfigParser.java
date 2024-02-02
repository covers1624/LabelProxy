package net.covers1624.lp.util;

import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.docker.data.DockerContainer;
import net.covers1624.quack.collection.FastStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.covers1624.lp.LabelProxy.PREFIX;

/**
 * Created by covers1624 on 4/11/23.
 */
public class ConfigParser {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String DEFAULT_GROUP = "default";
    private static final Pattern REGEX = Pattern.compile("^" + PREFIX + "(?:\\.(?<group>.*))?\\.(?<keyword>.*)$");

    public static List<ContainerConfiguration> parse(DockerContainer container, String ip) {
        Map<String, Map<String, String>> groups = new HashMap<>();

        Matcher matcher = REGEX.matcher("");
        for (Map.Entry<String, String> entry : container.config().getLabels(PREFIX + ".").entrySet()) {
            matcher.reset(entry.getKey());
            if (!matcher.find()) {
                throw new IllegalArgumentException("Malformed label " + entry.getKey());
            }
            String group = matcher.group("group");
            if (group == null) {
                group = DEFAULT_GROUP;
            }

            String keyword = matcher.group("keyword");
            if (keyword == null) throw new IllegalStateException("what?");

            groups.computeIfAbsent(group, e -> new LinkedHashMap<>())
                    .put(keyword, entry.getValue());
        }

        List<ContainerConfiguration> configs = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : groups.entrySet()) {
            String group = entry.getKey();
            Map<String, String> props = entry.getValue();
            configs.add(new ContainerConfiguration(
                    container.id(),
                    ip,
                    Objects.requireNonNull(props.get("host"), "'host' property required for group " + group),
                    Integer.parseInt(props.getOrDefault("port", "80")),
                    Boolean.parseBoolean(props.getOrDefault("https_redir", "true")),
                    Boolean.parseBoolean(props.getOrDefault("append_host_to_forwarded_host", "true")),
                    props.getOrDefault("location", "/"),
                    props.getOrDefault("proxy_pass", ""),
                    props.get("rewrite")
            ));
        }
        LOGGER.info("Parsed {} configs from {}", configs.size(), container.id());
        configs.forEach(LOGGER::info);

        return configs;
    }
}
