package net.covers1624.lp.nginx;

import net.covers1624.lp.Config;
import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.letsencrypt.LetsEncryptService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 3/11/23.
 */
public class NginxService {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Config config;
    private final LetsEncryptService letsEncrypt;

    private final Map<String, NginxHost> hosts = new HashMap<>();

    public NginxService(Config config, LetsEncryptService letsEncrypt) {
        this.config = config;
        this.letsEncrypt = letsEncrypt;
    }

    public void rebuild(Collection<ContainerConfiguration> configurations) {
        LOGGER.info("Rebuilding Nginx configs..");

        Map<String, NginxHost> hosts = new LinkedHashMap<>();
        for (ContainerConfiguration configuration : configurations) {
            NginxHost host = hosts.computeIfAbsent(configuration.host(), NginxHost::new);
            host.containers.add(configuration);
        }

        for (var iterator = hosts.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, NginxHost> entry = iterator.next();
            String host = entry.getKey();
            NginxHost oldHost = this.hosts.get(host);
            if (oldHost == null) {
                LOGGER.info("New nginx config for {}", host);
            } else if (oldHost.hasChanged(entry.getValue())) {
                LOGGER.info("Changed nginx config for {}", host);
            } else {
                LOGGER.info("Unmodified nginx config for {}", host);
                iterator.remove();
            }
        }
        if (hosts.isEmpty()) {
            LOGGER.error("LabelProxy detected container change, however, NginxService does not think any configs need to be changed...");
            return;
        }

        for (NginxHost host : hosts.values()) {
            markConfigPending(host);
            NginxConfigGenerator configGenerator = new NginxConfigGenerator(config, letsEncrypt, host);
            host.future = configGenerator.generate()
                    .thenAccept(config -> {
                        host.config = config;
                        activateConfig(host);
                    });
        }
    }

    public void markConfigPending(NginxHost host) {

    }

    public void activateConfig(NginxHost host) {

    }

    public static class NginxHost {

        public final String host;
        public final List<ContainerConfiguration> containers = new ArrayList<>();

        public @Nullable CompletableFuture<Void> future;
        private @Nullable String config;

        private NginxHost(String host) {
            this.host = host;
        }

        public boolean hasChanged(NginxHost newHost) {
            return !containers.equals(newHost.containers);
        }
    }
}
