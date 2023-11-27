package net.covers1624.lp.nginx;

import net.covers1624.lp.ContainerConfiguration;
import net.covers1624.lp.letsencrypt.LetsEncryptService;
import net.covers1624.lp.letsencrypt.LetsEncryptService.CertInfo;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.io.IndentPrintWriter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;

import static net.covers1624.lp.nginx.NginxConstants.SSL_CIPHERS;
import static net.covers1624.lp.nginx.NginxConstants.SSL_PROTOCOLS;

/**
 * Created by covers1624 on 3/11/23.
 */
public abstract class NginxConfigGenerator {

    protected final StringWriter sw = new StringWriter();
    protected final IndentPrintWriter pw = new IndentPrintWriter(new PrintWriter(sw, true));

    public abstract CompletableFuture<String> generate();

    protected void emitBlank() {
        pw.println();
    }

    protected void emit(String line) {
        pw.println(line + ";");
    }

    protected void emitBraced(String key, Runnable action) {
        pw.println(key + " {");
        pw.pushIndent();
        action.run();
        pw.popIndent();
        pw.println("}");
    }

    protected static String addStart(String add, String str) {
        if (str.startsWith(add)) return str;
        return add + str;
    }

    public static class NginxHttpConfigGenerator extends NginxConfigGenerator {

        private final LetsEncryptService letsEncrypt;
        private final NginxService.NginxHost host;

        public NginxHttpConfigGenerator(LetsEncryptService letsEncrypt, NginxService.NginxHost host) {
            this.letsEncrypt = letsEncrypt;
            this.host = host;
        }

        @Override
        public CompletableFuture<String> generate() {
            return letsEncrypt.getCertificates(host.host)
                    .thenApply(certInfo -> {
                        emitHttp();
                        emitHttps(certInfo);
                        return sw.toString();
                    });
        }

        private void emitHttp() {
            emitBraced("server", () -> {
                emit("listen 80");
                emit("listen [::]:80"); // One day we will have functioning ipv6
                emit("server " + host.host);
                emitBlank();
                emit("client_max_body_size 0M"); // I really could not care less, all endpoints get infinite upload.

                for (ContainerConfiguration container : host.containers) {
                    emitBlank();
                    emitBraced("location " + container.location(), () -> {
                        if (container.redirectToHttps()) {
                            emit("return 301 https://" + host.host + "$request_uri");
                        } else {
                            emitProxy(false, container);
                        }
                    });
                }
            });
        }

        private void emitHttps(CertInfo certInfo) {
            emitBraced("server", () -> {
                emit("listen 443 ssl http2");
                emit("listen [::]:443 ssl http2"); // One day we will have functioning ipv6
                emit("server " + host.host);
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
            String to = (https ? "https://" : "http://") + host + c.location();
            emit("proxy_pass " + from);
            emit("proxy_read_timeout 90");
            emit("proxy_max_temp_file_size 0");

            emit("proxy_set_header Host " + host.host + ":$server_port");
            emit("proxy_set_header X-Real-IP $remote_addr");
            emit("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for");
            emit("proxy_set_header X-Forwarded-Proto $scheme");
            emit("proxy_set_header X-Scheme $scheme");
            emit("proxy_set_header Referer $http_referer");
            emit("proxy_set_header Upgrade $http_upgrade");
            emit("proxy_set_header Connection \"upgrade\"");

            emit("proxy_set_header X-Forwarded-Server $host");
            emit("proxy_set_header X-Forwarded-Host $host");

            emit("proxy_redirect " + from + " " + to);
        }
    }
}
