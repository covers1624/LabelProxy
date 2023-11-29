package net.covers1624.lp;

import net.covers1624.lp.cloudflare.CloudflareService;
import net.covers1624.lp.docker.DockerService;
import net.covers1624.lp.docker.data.ContainerSummary;
import net.covers1624.lp.docker.data.DockerContainer;
import net.covers1624.lp.docker.data.DockerNetwork;
import net.covers1624.lp.letsencrypt.LetsEncryptService;
import net.covers1624.lp.nginx.NginxService;
import net.covers1624.lp.util.ConfigParser;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.curl4j.Curl4jHttpEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.file.Path;
import java.security.Security;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by covers1624 on 1/11/23.
 */
public class LabelProxy {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final String PREFIX = "LabelProxy";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public final Config config = Config.load(Path.of("./config.json"));
    public final Curl4jHttpEngine httpEngine = new Curl4jHttpEngine();
    public final DockerService docker = new DockerService(this, httpEngine);
    public final CloudflareService cloudflare = new CloudflareService(this, httpEngine);
    public final LetsEncryptService letsEncrypt = new LetsEncryptService(this, cloudflare);

    public final NginxService nginx = new NginxService(this, letsEncrypt);

    private final Map<String, List<ContainerConfiguration>> containerConfigs = new HashMap<>();
    private final Set<String> broken = new HashSet<>();

    private boolean running = true;

    public static void main(String[] args) {
        System.exit(new LabelProxy().mainI(args));
    }

    private int mainI(String[] args) {
        if (!prepareNetwork()) {
            return 1;
        }

        nginx.startNginx();

        int counter = 0;
        while (running) {
            boolean oneHourTrigger = counter % TimeUnit.HOURS.toSeconds(1) == 0;
            scanContainers();
            if (oneHourTrigger) {
                letsEncrypt.expiryScan();
            }
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException ignored) {
            }
            counter++;
        }

        return 0;
    }

    public void quit() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    private boolean prepareNetwork() {
        LOGGER.info("Preparing network {}..", config.docker.network);
        DockerNetwork network = docker.inspectNetwork(config.docker.network);
        if (network == null) {
            LOGGER.info(" Network does not exist.");
            if (!config.docker.createMissing) {
                LOGGER.error("Automatic network creation disabled. Either enable it or manually create the network.");
                return false;
            }
            LOGGER.info(" Creating network..");
            network = docker.createNetwork(config.docker.network);
            LOGGER.info("Network created! {}", network.id());
            return true;
        }
        LOGGER.info(" Network Exists, validating..");

        if (!network.driver().equals("bridge")) {
            LOGGER.error("Network must have 'bridge' Driver. Currently is: {}", network.driver());
            return false;
        }

        if (!network.scope().equals("local")) {
            LOGGER.error("Network must have 'local' Scope. Currently is: {}", network.scope());
            return false;
        }
        LOGGER.info("Network validated!");
        return true;
    }

    private void scanContainers() {
        boolean containersModified = false;

        List<ContainerSummary> summaries = docker.listContainers();
        Set<String> seen = new HashSet<>();
        for (ContainerSummary summary : summaries) {
            String id = summary.id();
            seen.add(id);

            DockerContainer container = docker.inspectContainer(id);
            if (containerConfigs.containsKey(id) || broken.contains(id)) continue;
            if (!container.config().hasLabelWithPrefix(PREFIX)) continue;
            LOGGER.info("New container found: {}", id);

            try {
                DockerContainer.Network network = container.networkSettings().networks().get(config.docker.network);
                if (network == null) {
                    LOGGER.info("Attaching container to {} network.", config.docker.network);
                    container = docker.connectNetwork(config.docker.network, id);
                    network = container.networkSettings().networks().get(config.docker.network);
                }

                List<ContainerConfiguration> containerConfiguration = ConfigParser.parse(container, network.ipAddress());
                containerConfigs.put(id, containerConfiguration);
                containersModified = true;
            } catch (Throwable ex) {
                LOGGER.error("Failed to build configuration for {}", id);
                broken.add(id);
            }
        }
        // Cleanup set, so it doesn't just fill up over time.
        broken.removeIf(e -> !seen.contains(e));

        for (var iterator = containerConfigs.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, List<ContainerConfiguration>> entry = iterator.next();
            String id = entry.getKey();
            if (seen.contains(id)) continue;

            LOGGER.info("Container removed: {}", id);
            iterator.remove();
            containersModified = true;
        }
        if (containersModified) {
            LOGGER.info("Modifications found.");
            nginx.rebuild(
                    FastStream.of(containerConfigs.values())
                            .flatMap(e -> e)
                            .toList()
            );
        }
    }
}
