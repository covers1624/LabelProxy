package net.covers1624.lp.util;

import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.ContainerConfiguration.HostConfiguration;
import net.covers1624.lp.docker.data.DockerContainer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Created by covers1624 on 4/11/23.
 */
public class ConfigParserTests {

    @Test
    public void testBasics() {
        assertEquals(
                config(
                        new HostConfiguration(
                                "abcd",
                                8080,
                                false,
                                "/abcd",
                                "1234"
                        )
                ),
                ConfigParser.parse(
                        container(Map.of(
                                "LabelProxy.host", "abcd",
                                "LabelProxy.port", "8080",
                                "LabelProxy.https_redir", "false",
                                "LabelProxy.location", "/abcd",
                                "LabelProxy.proxy_pass", "1234"
                        ))
                )
        );
    }

    @Test
    public void testMultipleGroups() {
        assertEquals(
                config(
                        new HostConfiguration(
                                "abcd",
                                8080,
                                false,
                                "/abcd",
                                "1234"
                        ),
                        new HostConfiguration(
                                "1234",
                                8181,
                                false,
                                "/1234",
                                "abcd"
                        )
                ),
                ConfigParser.parse(
                        container(Map.of(
                                "LabelProxy.host", "abcd",
                                "LabelProxy.port", "8080",
                                "LabelProxy.https_redir", "false",
                                "LabelProxy.location", "/abcd",
                                "LabelProxy.proxy_pass", "1234",
                                "LabelProxy.g1.host", "1234",
                                "LabelProxy.g1.port", "8181",
                                "LabelProxy.g1.https_redir", "false",
                                "LabelProxy.g1.location", "/1234",
                                "LabelProxy.g1.proxy_pass", "abcd"
                        ))
                )
        );
    }

    private static DockerContainer container(Map<String, String> labels) {
        return new DockerContainer(null, new DockerContainer.Config(labels), null);
    }

    private static ContainerConfiguration config(HostConfiguration... configs) {
        return new ContainerConfiguration(null, Arrays.asList(configs));
    }
}
