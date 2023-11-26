package net.covers1624.lp.letsencrypt;

import net.covers1624.quack.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Some simple garbage to run openssl and generate dhparam file.
 * <p>
 * Created by covers1624 on 4/11/23.
 */
public class DHParamGenerator extends Thread {

    private final Path dhParam;
    private final int bits;

    private final Object LOCK = new Object();
    private @Nullable Throwable error;

    DHParamGenerator(Path dhParam, int bits) {
        this.dhParam = dhParam;
        this.bits = bits;
    }

    @Override
    public void run() {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "openssl", "dhparam", "-out", IOUtils.makeParents(dhParam).toAbsolutePath().toString(), String.valueOf(bits)
            );
            Process process = builder.start();
            // OpenSSL writes to stderr
            InputStream is = process.getErrorStream();
            int n = 0;
            while (process.isAlive()) {
                try {
                    int b = is.read();
                    if (b == -1) break;
                    if (b == '\n') n = 0;

                    System.out.write(b);
                    System.out.flush();
                    if (++n == 80) {
                        n = 0;
                        System.out.println();
                    }
                } catch (IOException ignored) {
                    break;
                }
            }
        } catch (Throwable ex) {
            error = ex;
        }

        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    public void startAndWait() {
        start();
        synchronized (LOCK) {
            try {
                LOCK.wait();
            } catch (InterruptedException ex) {
                throw new RuntimeException("Interrupted waiting", ex);
            }
        }
        if (error != null) {
            throw new RuntimeException("Failed to generate dhparam.", error);
        }
    }
}
