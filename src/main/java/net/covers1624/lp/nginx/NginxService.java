package net.covers1624.lp.nginx;

import com.google.common.base.Charsets;
import net.covers1624.lp.Config;
import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.LabelProxy;
import net.covers1624.lp.letsencrypt.LetsEncryptService;
import net.covers1624.lp.nginx.NginxConfigGenerator.NginxHttpConfigGenerator;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.util.SneakyUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by covers1624 on 3/11/23.
 */
public class NginxService {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Config config;
    private final LabelProxy proxy;
    private final LetsEncryptService letsEncrypt;
    private final NginxProcess nginxProcess;
    private final Path configDir;
    private final Path tempDir;
    private final Path hostConfigDir;
    private final Path nginxPidFile;
    private final Path nginxAccessLog;
    private final Path nginxErrorLog;

    private final Map<String, NginxHost> hosts = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingHosts = new HashMap<>();

    public NginxService(LabelProxy proxy, LetsEncryptService letsEncrypt) {
        this.config = proxy.config;
        this.proxy = proxy;
        this.letsEncrypt = letsEncrypt;

        tempDir = config.tempDir.resolve("nginx");
        Path logsDir = config.logsDir.resolve("nginx");
        configDir = config.nginx.dir.resolve("conf");
        nginxPidFile = config.nginx.dir.resolve("nginx.pid");
        nginxAccessLog = logsDir.resolve("access.log");
        nginxErrorLog = logsDir.resolve("error.log");

        // Archive and delete configs.
        LOGGER.info("Startup. Archiving configs..");
        if (Files.exists(configDir)) {
            backupConfigs();
            deleteDirectory(configDir);
        }
        // Generate a new root config file.
        Path rootConfig = generateRootConfig(configDir);
        hostConfigDir = rootConfig.resolveSibling(rootConfig.getFileName() + ".d");
        try {
            Files.createDirectories(tempDir);
            Files.createDirectories(logsDir);
            Files.createDirectories(hostConfigDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to make directories.", ex);
        }

        // Start up Nginx.
        nginxProcess = new NginxProcess(proxy, configDir, rootConfig, nginxPidFile);
        nginxProcess.start();
    }

    public void rebuild(Collection<ContainerConfiguration> configurations) {
        LOGGER.info("Rebuilding Nginx configs..");

        Map<String, NginxHost> hosts = new LinkedHashMap<>();
        Set<String> deadHosts = new HashSet<>();
        for (ContainerConfiguration configuration : configurations) {
            NginxHost host = hosts.computeIfAbsent(configuration.host(), NginxHost::new);
            host.containers.add(configuration);
        }

        synchronized (this.hosts) {
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
            for (String host : this.hosts.keySet()) {
                if (!hosts.containsKey(host)) {
                    deadHosts.add(host);
                }
            }
        }
        if (hosts.isEmpty() && deadHosts.isEmpty()) {
            LOGGER.error("LabelProxy detected container change, however, NginxService does not think any configs need to be changed...");
            return;
        }

        synchronized (pendingHosts) {
            for (String host : hosts.keySet()) {
                CompletableFuture<Void> future = pendingHosts.get(host);
                if (future == null) continue;
                LOGGER.warn("Canceling pending host future for {} due to reconfigure.", host);
                future.cancel(true);
                pendingHosts.remove(host, future);
            }
        }

        for (NginxHost host : hosts.values()) {
            host.future = new NginxHttpConfigGenerator(letsEncrypt, host).generate()
                    .thenAccept(config -> {
                        host.config = config;
                        activateConfig(host);
                    });
            host.future.exceptionally(ex -> {
                LOGGER.error("Fatal error generating nginx config for {}", host.host, ex);
                return null;
            });
            synchronized (pendingHosts) {
                pendingHosts.put(host.host, host.future);
            }
        }
        if (!deadHosts.isEmpty()) {
            LOGGER.info("Removing hosts: {}", deadHosts);
            backupConfigs();
            for (String deadHost : deadHosts) {
                try {
                    Files.deleteIfExists(hostConfig(deadHost));
                } catch (IOException ex) {
                    LOGGER.error("Failed to delete host config.", ex);
                }
            }
            try {
                nginxProcess.hotReload();
            } catch (Throwable ex) {
                LOGGER.error("Nginx Hot reload failed!", ex);
            }
        }
    }

    public void activateConfig(NginxHost host) {
        if (host.config == null) throw new IllegalArgumentException("Unable to active host without a config.");

        if (pendingHosts.get(host.host) != host.future) {
            // TODO this could potentially happen in valid states.
            LOGGER.error("Invalid state. Applying nginx config when not pending?");
            return;
        }

        LOGGER.info("Activating nginx config for {}.", host.host);
        Path backup = backupConfigs();
        boolean testSuccess = false;
        try {
            Files.writeString(hostConfig(host.host), host.config, Charsets.UTF_8);
            testSuccess = nginxProcess.testConfig();
        } catch (IOException ex) {
            LOGGER.error("Failed to run config test for {}", host.host, ex);
        }
        if (!testSuccess) {
            backupConfigs("failed");
            LOGGER.error("Generated invalid Nginx config.");
            restoreConfigs(backup);
            return;
        }

        try {
            nginxProcess.hotReload();
        } catch (Throwable ex) {
            LOGGER.error("Failed to hot reload nginx.");
            restoreConfigs(backup);
            return;
        }

        // TODO:
        //  On some common thread:
        //  Archive old config version.
        //  Write config.
        //  Test nginx with config change.
        //   If failure, report failed, restore config
        //   If success, reload nginx /nginx -s reload
        synchronized (hosts) {
            hosts.put(host.host, host);
        }
        LOGGER.info("Nginx updated!");
    }

    private Path generateRootConfig(Path configDir) {
        try {
            Path mimeConfigFile = IOUtils.makeParents(configDir.resolve("mime.conf"));
            Files.writeString(mimeConfigFile, new NginxConfigGenerator.Simple() {

                @Override
                public String generate() {
                    emitBraced("types", () -> {
                        for (Map.Entry<String, String[]> entry : NginxConstants.MIME_TYPES.entrySet()) {
                            emit(entry.getKey() + " " + String.join(" ", entry.getValue()));
                        }
                    });
                    return sw.toString();
                }

            }.generate());

            Path nginxRootConfig = IOUtils.makeParents(configDir.resolve("nginx.conf"));
            Files.writeString(nginxRootConfig, new NginxConfigGenerator.Simple() {

                @Override
                public String generate() {
                    String currUser = System.getProperty("user.name");
                    boolean useCurrUser = config.nginx.user.equals("$whoami");
                    if (!useCurrUser || !currUser.equals("root")) {
                        emit("user " + (useCurrUser ? currUser : config.nginx.user));
                    }
                    emit("worker_processes " + config.nginx.workers);
                    emitBlank();
                    emit("error_log " + nginxErrorLog.toAbsolutePath().normalize() + " notice");
                    emit("pid " + nginxPidFile.toAbsolutePath().normalize());
                    emitBlank();
                    emitBraced("events", () -> {
                        emit("worker_connections " + config.nginx.workerConnections);
                    });
                    emitBlank();
                    emitBraced("http", () -> {
                        emit("include " + mimeConfigFile.toAbsolutePath().normalize());
                        emit("default_type application/octet-stream");
                        emitBlank();
                        emit("client_body_temp_path " + tempDir.resolve("client-body").toAbsolutePath().normalize() + " 1 2");
                        emit("fastcgi_temp_path " + tempDir.resolve("fastcgi").toAbsolutePath().normalize() + " 1 2");
                        emit("uwsgi_temp_path " + tempDir.resolve("uwsgi").toAbsolutePath().normalize() + " 1 2");
                        emit("scgi_temp_path " + tempDir.resolve("scgi").toAbsolutePath().normalize() + " 1 2");
                        emitBlank();
                        emit("log_format main '" + config.nginx.logFormat + "'");
                        emit("access_log " + nginxAccessLog.toAbsolutePath().normalize() + " main");
                        emitBlank();
                        emit("sendfile on");
                        emitBlank();
                        emit("keepalive_timeout 65");
                        emitBlank();
                        emit("include " + nginxRootConfig.toAbsolutePath().normalize() + ".d/*.conf");
                    });

                    return sw.toString();
                }
            }.generate());
            return nginxRootConfig;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to generate root config.", ex);
        }
    }

    private Path backupConfigs() {
        return backupConfigs("");
    }

    private Path backupConfigs(String prefix) {
        if (!prefix.isEmpty()) {
            prefix += "-";
        }
        Path zip = config.nginx.dir.resolve("backups/" + prefix + "config-" + System.currentTimeMillis() + ".zip");
        LOGGER.info("Creating config backup {}", zip.getFileName());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(IOUtils.makeParents(zip)))) {
            try (Stream<Path> files = Files.walk(configDir)) {
                for (Path file : (Iterable<? extends Path>) files::iterator) {
                    if (Files.isDirectory(file)) continue;
                    zos.putNextEntry(new ZipEntry(configDir.relativize(file).toString()));
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to backup zip.", ex);
        }
        return zip;
    }

    private void restoreConfigs(Path zip) {
        LOGGER.info("Restoring configs..");
        deleteDirectory(configDir);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Files.copy(zin, IOUtils.makeParents(configDir.resolve(entry.getName())));
            }
        } catch (IOException ex) {
            proxy.quit();
            nginxProcess.quit();
            LOGGER.error("Failed to restore configs.", ex);
            throw new RuntimeException("Failed to restore configs.", ex);
        }
    }

    private Path hostConfig(String host) {
        return hostConfigDir.resolve(host + ".conf");
    }

    private static void deleteDirectory(Path file) {
        if (Files.notExists(file)) return;

        try (Stream<Path> files = Files.walk(file)) {
            files.filter(Files::exists)
                    .sorted(Comparator.reverseOrder())
                    .forEach(e -> {
                        try {
                            Files.deleteIfExists(e);
                        } catch (IOException ex) {
                            if (Files.exists(e)) {
                                SneakyUtils.throwUnchecked(ex);
                            }
                        }
                    });

        } catch (IOException ex) {
            SneakyUtils.throwUnchecked(ex);
        }
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
