package net.covers1624.lp.util;

import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.docker.data.DockerContainer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Created by covers1624 on 4/11/23.
 */
public class ConfigParserTests {

    @Test
    public void testBasics() {
        assertEquals(
                List.of(
                        new ContainerConfiguration(
                                null,
                                null,
                                "abcd",
                                8080,
                                false,
                                true,
                                "/abcd",
                                "1234",
                                null
                        )
                ),
                ConfigParser.parse(
                        container(Map.of(
                                "LabelProxy.host", "abcd",
                                "LabelProxy.port", "8080",
                                "LabelProxy.https_redir", "false",
                                "LabelProxy.location", "/abcd",
                                "LabelProxy.proxy_pass", "1234"
                        )),
                        null
                )
        );
    }

    @Test
    public void testMultipleGroups() {
        assertEquals(
                List.of(
                        new ContainerConfiguration(
                                null,
                                null,
                                "abcd",
                                8080,
                                false,
                                true,
                                "/abcd",
                                "1234",
                                null
                        ),
                        new ContainerConfiguration(
                                null,
                                null,
                                "1234",
                                8181,
                                false,
                                true,
                                "/1234",
                                "abcd",
                                null
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
                        )),
                        null
                )
        );
    }

    private static DockerContainer container(Map<String, String> labels) {
        return new DockerContainer(null, new DockerContainer.Config(labels), null);
    }
}
