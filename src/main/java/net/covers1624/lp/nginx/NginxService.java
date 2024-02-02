package net.covers1624.lp.nginx;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.covers1624.lp.Config;
import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.LabelProxy;
import net.covers1624.lp.letsencrypt.LetsEncryptService;
import net.covers1624.quack.collection.FastStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static net.covers1624.lp.logging.Markers.DISCORD;
import static net.covers1624.lp.nginx.NginxConstants.SSL_CIPHERS;
import static net.covers1624.lp.nginx.NginxConstants.SSL_PROTOCOLS;

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
    private final Path rootConfig;
    private final Path hostConfigDir;

    private final @Nullable Path tempDir;
    private final Path nginxPidFile;
    private final Path nginxAccessLog;
    private final Path nginxErrorLog;

    private final Map<String, NginxHost> hosts = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingHosts = new HashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setDaemon(true).setNameFormat("Nginx Config Builder %d").build());
    private final ExecutorService NGINX_APPLY_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).setNameFormat("Nginx Config Applicator").build());

    public NginxService(LabelProxy proxy, LetsEncryptService letsEncrypt) {
        this.config = proxy.config;
        this.proxy = proxy;
        this.letsEncrypt = letsEncrypt;

        configDir = config.nginx.dir.resolve("conf");
        rootConfig = configDir.resolve("nginx.conf");
        hostConfigDir = configDir.resolve("nginx.conf.d");

        tempDir = !LabelProxy.RUNNING_AS_ROOT ? config.tempDir.resolve("nginx") : null;
        Path logsDir = config.logsDir.resolve("nginx");
        nginxPidFile = config.nginx.dir.resolve("nginx.pid");
        nginxAccessLog = logsDir.resolve("access.log");
        nginxErrorLog = logsDir.resolve("error.log");

        try {
            if (tempDir != null) Files.createDirectories(tempDir);
            Files.createDirectories(logsDir);
            Files.createDirectories(hostConfigDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to make directories.", ex);
        }

        nginxProcess = new NginxProcess(proxy, configDir, rootConfig, nginxPidFile, nginxAccessLog, nginxErrorLog);
    }

    public boolean validate() {
        if (LabelProxy.RUNNING_AS_ROOT && !ensureNginxWorkerUserExists()) {
            return false;
        }

        Path nginxExecutable = Path.of(config.nginx.executable);
        if (Files.notExists(nginxExecutable)) {
            LOGGER.error("Nginx executable '{}' does not exist.", nginxExecutable);
            return false;
        }

        if (!Files.isExecutable(nginxExecutable)) {
            LOGGER.error("Nginx executable '{}' is not executable.", nginxExecutable);
            return false;
        }

        return true;
    }

    private boolean ensureNginxWorkerUserExists() {
        LOGGER.info("Checking if nginx worker user '{}' exists.", config.nginx.user);
        try {
            Process proc = new ProcessBuilder("id", "-u", config.nginx.user)
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes(); // We do nothing with this, but incase it blocks as no input has been read.
            proc.onExit().join();

            return proc.exitValue() == 0;
        } catch (IOException ex) {
            LOGGER.error("Failed to query if nginx worker user exists.", ex);
        }
        return false;
    }

    public void startNginx() {
        if (nginxProcess.getState() != Thread.State.NEW) throw new IllegalStateException("Nginx already started.");

        LOGGER.info("Startup. Archiving configs..");
        if (Files.exists(configDir)) {
            backupConfigs();
            deleteDirectory(configDir);
        }
        generateRootConfig();
        nginxProcess.start();
    }

    public void stopNginx() {
        nginxProcess.quit();
    }

    public void rotateLogs() {
        nginxProcess.rotateLogs();
    }

    public void onRenewCertificates(LetsEncryptService.CertInfo newInfo) {
        NginxHost host = hosts.get(newInfo.host());
        buildConfig(host);
    }

    public void rebuild(Collection<ContainerConfiguration> configurations) {
        LOGGER.info(DISCORD, "Rebuilding Nginx configs..");

        Map<String, NginxHost> hosts = new LinkedHashMap<>();
        Set<String> deadHosts = new HashSet<>();
        Set<String> unmodified = new HashSet<>();
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
                    LOGGER.info(DISCORD, " New nginx config for {}", host);
                } else if (oldHost.hasChanged(entry.getValue())) {
                    LOGGER.info(DISCORD, " Changed nginx config for {}", host);
                } else {
                    LOGGER.info(" Unmodified nginx config for {}", host);
                    iterator.remove();
                    unmodified.add(host);
                }
            }
            for (String host : this.hosts.keySet()) {
                if (!hosts.containsKey(host) && !unmodified.contains(host)) {
                    deadHosts.add(host);
                }
            }
        }
        if (hosts.isEmpty() && deadHosts.isEmpty()) {
            LOGGER.error(DISCORD, "LabelProxy detected container change, however, NginxService does not think any configs need to be changed...");
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
            buildConfig(host);
        }

        if (!deadHosts.isEmpty()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                LOGGER.info(DISCORD, "Removing hosts: {}", deadHosts);
                backupConfigs();
                synchronized (this.hosts) {
                    for (String deadHost : deadHosts) {
                        this.hosts.remove(deadHost);
                        try {
                            Files.deleteIfExists(hostConfig(deadHost));
                        } catch (IOException ex) {
                            LOGGER.error(DISCORD, "Failed to delete host config.", ex);
                        }
                    }
                }
                try {
                    nginxProcess.hotReload();
                } catch (Throwable ex) {
                    LOGGER.error(DISCORD, "Nginx Hot reload failed!", ex);
                }
            }, NGINX_APPLY_EXECUTOR);
            future.exceptionally(ex -> {
                LOGGER.error("DISCORD, Failed to remove dead nginx hosts and reload.", ex);
                return null;
            });
        }
    }

    private void buildConfig(NginxHost host) {
        synchronized (pendingHosts) {
            host.future = new NginxHttpConfigGenerator(letsEncrypt, host).generate()
                    .thenAcceptAsync(config -> {
                        host.config = config;
                        activateConfig(host);
                    }, NGINX_APPLY_EXECUTOR);
            host.future.exceptionally(ex -> {
                LOGGER.error(DISCORD, "Fatal error generating nginx config for {}", host.host, ex);
                return null;
            });
            pendingHosts.put(host.host, host.future);
        }
    }

    public void activateConfig(NginxHost host) {
        if (host.config == null) throw new IllegalArgumentException("Unable to active host without a config.");

        synchronized (pendingHosts) {
            if (pendingHosts.get(host.host) != host.future) {
                // TODO this could potentially happen in valid states.
                LOGGER.error(DISCORD, "Invalid state. Applying nginx config when not pending?");
                return;
            }
        }

        LOGGER.info(DISCORD, "Activating nginx config for {}.", host.host);
        Path backup = backupConfigs();
        boolean testSuccess = false;
        try {
            Files.writeString(IOUtils.makeParents(hostConfig(host.host)), host.config, Charsets.UTF_8);
            testSuccess = nginxProcess.testConfig();
        } catch (IOException ex) {
            LOGGER.error(DISCORD, "Failed to run config test for {}", host.host, ex);
        }
        if (!testSuccess) {
            backupConfigs("failed");
            LOGGER.error(DISCORD, "Generated invalid Nginx config.");
            restoreConfigs(backup);
            return;
        }

        try {
            nginxProcess.hotReload();
        } catch (Throwable ex) {
            LOGGER.error(DISCORD, "Failed to hot reload nginx.");
            restoreConfigs(backup);
            return;
        }

        synchronized (hosts) {
            hosts.put(host.host, host);
        }
        synchronized (pendingHosts) {
            pendingHosts.remove(host.host);
        }
        LOGGER.info(DISCORD, "Nginx updated!");
    }

    private void generateRootConfig() {
        try {
            Path mimeConfigFile = IOUtils.makeParents(configDir.resolve("mime.conf"));
            Files.writeString(mimeConfigFile, new NginxConfigGenerator.Simple() {

                @Override
                public String generate() {
                    emit("types_hash_max_size 4096");
                    emitBraced("types", () -> {
                        for (Map.Entry<String, String[]> entry : NginxConstants.MIME_TYPES.entrySet()) {
                            emit(entry.getKey() + " " + String.join(" ", entry.getValue()));
                        }
                    });
                    return sw.toString();
                }

            }.generate());

            Path nginxRootConfig = IOUtils.makeParents(rootConfig);
            Files.writeString(nginxRootConfig, new NginxConfigGenerator.Simple() {

                @Override
                public String generate() {
                    if (LabelProxy.RUNNING_AS_ROOT) {
                        emit("user " + config.nginx.user);
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
                        // This exists mainly for dev-time. When we don't run LabelProxy and the root nginx server as root.
                        if (tempDir != null) {
                            emitBlank();
                            emit("client_body_temp_path " + tempDir.resolve("client-body").toAbsolutePath().normalize() + " 1 2");
                            emit("fastcgi_temp_path " + tempDir.resolve("fastcgi").toAbsolutePath().normalize() + " 1 2");
                            emit("uwsgi_temp_path " + tempDir.resolve("uwsgi").toAbsolutePath().normalize() + " 1 2");
                            emit("scgi_temp_path " + tempDir.resolve("scgi").toAbsolutePath().normalize() + " 1 2");
                        }
                        emitBlank();
                        emit("log_format main '" + config.nginx.logFormat + "'");
                        emit("access_log " + nginxAccessLog.toAbsolutePath().normalize() + " main");
                        emitBlank();
                        emit("sendfile on");
                        emitBlank();
                        emit("keepalive_timeout 65");
                        emitBlank();
                        emitBraced("server", () -> {
                            emit("listen 80 default_server");
                            emit("listen [::]:80 default_server");
                            emit("return 444");
                        });
                        emitBraced("server", () -> {
                            emit("listen 443 ssl default_server");
                            emit("listen 443 quic default_server reuseport");
                            emit("listen [::]:443 ssl default_server");
                            emit("listen [::]:443 quic default_server reuseport");
                            emit("ssl_reject_handshake on");
                        });
                        emitBlank();
                        emit("include " + nginxRootConfig.toAbsolutePath().normalize() + ".d/*.conf");
                    });

                    return sw.toString();
                }
            }.generate());
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

    public static class NginxHttpConfigGenerator extends NginxConfigGenerator {

        private final LetsEncryptService letsEncrypt;
        private final NginxHost host;

        public NginxHttpConfigGenerator(LetsEncryptService letsEncrypt, NginxHost host) {
            this.letsEncrypt = letsEncrypt;
            this.host = host;
        }

        public CompletableFuture<String> generate() {
            return letsEncrypt.getCertificates(host.host)
                    .thenApplyAsync(certInfo -> {
                        emitHttp();
                        emitHttps(certInfo);
                        return sw.toString();
                    }, EXECUTOR);
        }

        private void emitHttp() {
            emitBraced("server", () -> {
                emit("listen 80");
                emit("listen [::]:80"); // One day we will have functioning ipv6
                emit("server_name " + host.host);
                emitBlank();
                emit("client_max_body_size 0M"); // I really could not care less, all endpoints get infinite upload.

                for (ContainerConfiguration container : host.containers) {
                    emitBlank();
                    emitBraced("location " + container.location(), () -> {
                        if (container.redirectToHttps()) {
                            emit("add_header Alt-Svc 'h3=\":443\"; ma=86400'");
                            emit("return 301 https://" + host.host + "$request_uri");
                        } else {
                            emitProxy(false, container);
                        }
                    });
                }
            });
        }

        private void emitHttps(LetsEncryptService.CertInfo certInfo) {
            emitBraced("server", () -> {
                emit("listen 443 ssl");
                emit("listen 443 quic");
                emit("listen [::]:443 ssl"); // One day we will have functioning ipv6
                emit("listen [::]:443 quic");
                emit("http2 on");
                emit("http3 on");
                emit("server_name " + host.host);
                emitBlank();
                emit("client_max_body_size 0M"); // I really could not care less, all endpoints get infinite upload.
                emitBlank();
                emit("ssl_dhparam " + letsEncrypt.dhParam);
                emit("ssl_certificate " + certInfo.fullChain());
                emit("ssl_certificate_key " + certInfo.privKey());
                emit("ssl_trusted_certificate " + certInfo.chain());
                emitBlank();
                emit("ssl_protocols " + FastStream.of(SSL_PROTOCOLS).join(" "));
                emit("ssl_prefer_server_ciphers on");
                emit("ssl_ciphers " + FastStream.of(SSL_CIPHERS).join(":"));
                emit("ssl_ecdh_curve auto");
                emitBlank();
                emit("ssl_session_cache shared:SSL:1m");
                emit("ssl_session_tickets off");
                emitBlank();
                emit("ssl_stapling on");
                emit("ssl_stapling_verify on");
                emitBlank();
                emit("resolver 1.1.1.1 8.8.8.8 valid=300s");
                emit("resolver_timeout 5s");
                emitBlank();
                emit("add_header Strict-Transport-Security \"max-age=63072000; includeSubdomains\"");
                emit("add_header X-Frame-Options SAMEORIGIN");
                emit("add_header X-Content-Type-Options nosniff");

                for (ContainerConfiguration container : host.containers) {
                    emitBlank();
                    emitBraced("location " + container.location(), () -> {
                        emitProxy(true, container);
                    });
                }
            });
        }

        private void emitProxy(boolean https, ContainerConfiguration c) {
            String from = "http://" + c.ip() + ":" + c.port() + addStart("/", c.proxyPass());
            String to = (https ? "https://" : "http://") + host.host;
            emit("proxy_pass " + from);
            if (c.rewrite() != null) {
                emit("rewrite " + c.rewrite());
            }
            emit("proxy_read_timeout 90");
            emit("proxy_max_temp_file_size 0");
            emitBlank();
            emit("proxy_set_header Host $host" + (c.appendPortToForwardedHost() ? ":$server_port" : ""));
            emit("proxy_set_header X-Real-IP $remote_addr");
            emit("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for");
            emit("proxy_set_header X-Forwarded-Proto $scheme");
            emit("proxy_set_header X-Scheme $scheme");
            emit("proxy_set_header Referer $http_referer");
            emit("proxy_set_header Upgrade $http_upgrade");
            emit("proxy_set_header Connection \"upgrade\"");
            emitBlank();
            emit("proxy_set_header X-Forwarded-Server $host");
            emit("proxy_set_header X-Forwarded-Host $host");
            emitBlank();
            emit("proxy_redirect " + from + " " + to);
            emitBlank();
            emit("add_header Alt-Svc 'h3=\":443\"; ma=86400'");
        }
    }
}
