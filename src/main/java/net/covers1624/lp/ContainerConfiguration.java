package net.covers1624.lp;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 2/11/23.
 */
public record ContainerConfiguration(
        // The container ID.
        String id,
        // The container IP on the http network.
        String ip,
        // The hostname
        String host,
        // The port within the container.
        int port,
        // If the proxy should force redirect http to https.
        boolean redirectToHttps,
        // If the proxy should append the port number to the forwarded Host header.
        boolean verboseForwardHost,
        // The location to use, regex.
        String location,
        // The proxy_pass upstream pattern. $blah variables can be used from location.
        String proxyPass,
        // Explicit allow directives.
        List<String> allow,
        // Explicit deny directives.
        List<String> deny,
        // Unknown keywords, mapped directly to nginx directives.
        Map<String, List<String>> unknownKeywords
) {
}
