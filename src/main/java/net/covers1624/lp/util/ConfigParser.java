package net.covers1624.lp.util;

import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.docker.data.DockerContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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
    // We technically don't read id, we only use it here to match properly.
    private static final Pattern REGEX = Pattern.compile("^" + PREFIX + "(?:\\.(?<group>\\w*))?\\.(?<keyword>[a-zA-Z_]+)(?:.(?<id>\\d*))?$");

    public static List<ContainerConfiguration> parse(DockerContainer container, String ip) {
        Map<String, Map<String, List<String>>> groups = new HashMap<>();

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
                    .computeIfAbsent(keyword, e -> new ArrayList<>())
                    .add(entry.getValue());
        }

        List<ContainerConfiguration> configs = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : groups.entrySet()) {
            LOGGER.info("Parsing group: {}", entry.getKey());
            Map<String, List<String>> props = entry.getValue();

            configs.add(new ContainerConfiguration(
                    container.id(),
                    ip,
                    single(props.remove("host"), "host"),
                    Integer.parseInt(singleOrDefault(props.remove("port"), "80")),
                    Boolean.parseBoolean(singleOrDefault(props.remove("https_redir"), "true")),
                    Boolean.parseBoolean(singleOrDefault(props.remove("verbose_forwarded_host"), "true")),
                    singleOrDefault(props.remove("location"), "/"),
                    singleOrDefault(props.remove("proxy_pass"), ""),
                    props
            ));
            if (!props.isEmpty()) {
                LOGGER.info("The following properties were not recognised. These will be mapped to nginx directives.");
                props.forEach((k, v) -> LOGGER.info(" {}: {}", k, v));
            }
        }
        LOGGER.info("Parsed {} configs from {}", configs.size(), container.id());
        configs.forEach(LOGGER::info);

        return configs;
    }

    private static @Nullable String singleOpt(@Nullable List<String> strings) {
        if (strings == null || strings.isEmpty()) return null;
        if (strings.size() > 1) throw new IllegalArgumentException("Option expects a single argument. Got: " + strings);

        return strings.get(0);
    }

    private static String singleOrDefault(@Nullable List<String> strings, String _default) {
        String arg = singleOpt(strings);
        return arg != null ? arg : _default;
    }

    private static String single(@Nullable List<String> strings, String opt) {
        String arg = singleOpt(strings);
        if (arg == null) {
            throw new IllegalArgumentException("Option '" + opt + "' is required.");
        }
        return arg;
    }
}
