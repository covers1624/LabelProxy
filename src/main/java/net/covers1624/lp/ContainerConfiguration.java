package net.covers1624.lp;

import java.util.List;

/**
 * Created by covers1624 on 2/11/23.
 */
public record ContainerConfiguration(
        // The container ID.
        String id,
        // The hosts which this container is proxying
        List<HostConfiguration> hosts
) {

    public record HostConfiguration(
            // The hostname
            String host,
            // The port within the container.
            int port,
            // If the proxy should force redirect http to https.
            boolean redirectToHttps,
            // The location to use, regex.
            String location,
            // The proxy_pass upstream pattern. $blah variables can be used from location.
            String proxyPass
    ) {
    }
}
