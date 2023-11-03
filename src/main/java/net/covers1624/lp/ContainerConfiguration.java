package net.covers1624.lp;

import java.util.List;

/**
 * Created by covers1624 on 2/11/23.
 */
public record ContainerConfiguration(
        String id,
        List<String> hosts,
        int port
) {
}
