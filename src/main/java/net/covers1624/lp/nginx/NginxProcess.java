package net.covers1624.lp.nginx;

import net.covers1624.lp.Config;
import net.covers1624.lp.LabelProxy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by covers1624 on 27/11/23.
 */
public class NginxProcess extends Thread {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LabelProxy proxy;
    private final Config config;
    private final Path configDir;
    private final Path rootConfig;
    private final Path pidFile;
    private Process process;

    public NginxProcess(LabelProxy proxy, Path configDir, Path rootConfig, Path pidFile) {
        this.proxy = proxy;
        this.config = proxy.config;
        this.configDir = configDir;
        this.rootConfig = rootConfig;
        this.pidFile = pidFile;
        setName("Nginx Monitor");
        setDaemon(false);
    }

    public void quit() {
        LOGGER.warn("Requested exit of Nginx.");
        process.destroy();
    }

    public boolean testConfig() throws IOException {
        return signalNginx("-t") == 0;
    }

    public void hotReload() throws IOException {
        int ret = signalNginx("-s", "reload");
        if (ret != 0) {
            throw new IllegalStateException("Nginx returned: " + ret);
        }
    }

    private int signalNginx(String... args) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                config.nginx.executable,
                "-p", configDir.toAbsolutePath().normalize().toString(),
                "-c", rootConfig.toAbsolutePath().normalize().toString(),
                "-g", "daemon off;"
        );
        Collections.addAll(builder.command(), args);
        builder.redirectErrorStream(true);
        Process proc = builder.start();
        gobbleProcess(proc, Process::getInputStream, LOGGER::info);
        proc.onExit().join();
        return proc.exitValue();
    }

    @Override
    public void run() {
        logVersion();
        while (proxy.isRunning()) {
            LOGGER.info("Starting Nginx..");
            try {
                long startTime = System.currentTimeMillis();
                runNginx();
                if ((System.currentTimeMillis() - startTime) < 500) {
                    LOGGER.error("Nginx was running for less than 500ms. Its probably broken..");
                    proxy.quit();
                    break;
                }
            } catch (AbortNginx ex) {
                LOGGER.error("Nginx thread aborting.", ex);
                break;
            } catch (Throwable ex) {
                LOGGER.fatal("Nginx thread crashed.", ex);
            }
        }
        LOGGER.info("Nginx thread exiting.");
    }

    private void runNginx() throws AbortNginx, IOException {
        killNginxIfRunning();
        ProcessBuilder builder = new ProcessBuilder(
                config.nginx.executable,
                "-p", configDir.toAbsolutePath().normalize().toString(),
                "-c", rootConfig.toAbsolutePath().normalize().toString(),
                "-g", "daemon off;"
        );
        builder.redirectErrorStream(true);
        process = builder.start();
        gobbleProcess(process, Process::getInputStream, LOGGER::info);
        process.onExit().join();
    }

    private void killNginxIfRunning() throws AbortNginx {
        if (Files.notExists(pidFile)) return;
        String content;
        try {
            content = Files.readString(pidFile).strip();
        } catch (IOException ex) {
            LOGGER.warn("Failed to read nginx pid file.", ex);
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(content);
        } catch (NumberFormatException ex) {
            LOGGER.warn("Pid file did not contain parsable integer. " + content, ex);
            return;
        }

        ProcessHandle proc = ProcessHandle.of(pid).orElse(null);
        if (proc == null || !proc.isAlive()) return;
        if (proc.info().command().filter(e -> e.contains("nginx")).isEmpty()) return;

        LOGGER.warn("Nginx is still running! Killing..");
        if (!proc.destroy()) {
            proxy.quit();
            throw new AbortNginx("Failed to request nginx process destruction.");
        }
        LOGGER.info("Waiting for nginx to exit..");
        proc.onExit().join();
    }

    private void logVersion() {
        ProcessBuilder pb = new ProcessBuilder(
                config.nginx.executable,
                "-V"
        );
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> {
                    if (line.startsWith("configure arguments")) return;
                    LOGGER.info(line);
                });
            }
            if (proc.exitValue() != 0) {
                throw new RuntimeException("Nginx exited with error.");
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to start and log Nginx version.");
        }
    }

    private static void gobbleProcess(Process proc, Function<Process, InputStream> func, Consumer<String> cons) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(func.apply(proc), StandardCharsets.UTF_8))) {
                String line;
                while (proc.isAlive() && (line = reader.readLine()) != null) {
                    cons.accept(line);
                }
            } catch (IOException ex) {
                LOGGER.error("Gobbler crashed.", ex);
            }
        });
        thread.setName("Process Stream Gobbler");
        thread.setDaemon(true);
        thread.start();
    }

    private static class AbortNginx extends Exception {

        public AbortNginx(String message) {
            super(message);
        }

        public AbortNginx(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
