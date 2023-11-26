package net.covers1624.lp;

import net.covers1624.lp.cloudflare.CloudflareService;
import net.covers1624.lp.docker.DockerService;
import net.covers1624.lp.docker.data.ContainerSummary;
import net.covers1624.lp.docker.data.DockerContainer;
import net.covers1624.lp.docker.data.DockerNetwork;
import net.covers1624.lp.letsencrypt.LetsEncryptService;
import net.covers1624.lp.util.ConfigParser;
import net.covers1624.quack.net.httpapi.curl4j.Curl4jHttpEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.file.Path;
import java.security.Security;
import java.util.*;

/**
 * Created by covers1624 on 1/11/23.
 */
public class LabelProxy {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final String PREFIX = "LabelProxy";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final Config config = Config.load(Path.of("./config.json"));
    private final Curl4jHttpEngine httpEngine = new Curl4jHttpEngine();
    private final DockerService docker = new DockerService(config, httpEngine);
    private final CloudflareService cloudflare = new CloudflareService(config, httpEngine);
    private final LetsEncryptService letsEncrypt = new LetsEncryptService(config, cloudflare);

    private final Map<String, ContainerConfiguration> containerConfigs = new HashMap<>();
    private final Set<String> broken = new HashSet<>();

    public static void main(String[] args) {
        System.exit(new LabelProxy().mainI(args));
    }

    private int mainI(String[] args) {
        if (!prepareNetwork()) {
            return 1;
        }

        // Main loop:
        // - Detect new containers:
        //  - Generate internal config
        //  - Attach HTTP network
        // - Detect old containers:
        //  - Delete from internal config
        // - Rebuild nginx configs for each container.
        //  - Detect new certificates needed.
        //   - Create internal certificate objects.
        // - Check for any outdated or new certificates.
        //  - Mark nginx config as pending awaiting certificates.
        // - LetsEncrypt renew certificates.
        //  - Cloudflare DNS by default for auth.
        //  - If All containers on domain say they want to _not_ use DNS
        //   - Insert temporary nginx config for .well-known endpoint, etc.
        // - Hot reload nginx.
        scanContainers();

        return 0;
    }

    private boolean prepareNetwork() {
        LOGGER.info("Preparing network {}..", config.networkName);
        DockerNetwork network = docker.inspectNetwork(config.networkName);
        if (network == null) {
            LOGGER.info(" Network does not exist.");
            if (!config.createMissingNetwork) {
                LOGGER.error("Automatic network creation disabled. Either enable it or manually create the network.");
                return false;
            }
            LOGGER.info(" Creating network..");
            network = docker.createNetwork(config.networkName);
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

            try {
                ContainerConfiguration containerConfiguration = ConfigParser.parse(container);
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
            Map.Entry<String, ContainerConfiguration> entry = iterator.next();
            String id = entry.getKey();
            if (seen.contains(id)) continue;

            LOGGER.info("Container removed: {}", id);
            iterator.remove();
            containersModified = true;
        }
        if (containersModified) {
            LOGGER.info("Modifications found.");
        }
    }
}
