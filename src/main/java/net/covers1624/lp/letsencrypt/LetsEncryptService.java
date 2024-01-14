package net.covers1624.lp.letsencrypt;

import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.JsonAdapter;
import net.covers1624.lp.Config;
import net.covers1624.lp.LabelProxy;
import net.covers1624.lp.cloudflare.CloudflareService;
import net.covers1624.lp.cloudflare.data.dns.CreateDNSRecordResponse;
import net.covers1624.lp.cloudflare.data.dns.DnsRecord;
import net.covers1624.lp.cloudflare.data.dns.RecordBuilder;
import net.covers1624.lp.util.CryptoUtils;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.gson.PathTypeAdapter;
import net.covers1624.quack.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static net.covers1624.lp.cloudflare.data.dns.RecordType.TXT;

/**
 * Created by covers1624 on 4/11/23.
 */
public class LetsEncryptService {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).setNameFormat("LetsEncrypt Executor").build());

    private final Map<String, CertInfo> certs = new HashMap<>();
    private final Map<String, CompletableFuture<CertInfo>> pending = new HashMap<>();

    private final LabelProxy proxy;
    private final Config config;
    private final CloudflareService cloudflare;

    public final Path dhParam;

    private final Path cacheDir;
    private final Path certsDir;
    private final Session session;
    private final Supplier<Account> account;

    public LetsEncryptService(LabelProxy proxy, CloudflareService cloudflare) {
        this.proxy = proxy;
        this.config = proxy.config;
        this.cloudflare = cloudflare;

        dhParam = config.letsEncrypt.dir.resolve("dhparam.pem").toAbsolutePath();

        cacheDir = config.letsEncrypt.dir;
        certsDir = cacheDir.resolve("certs");
        String staging = config.letsEncrypt.staging ? "staging" : "";
        session = new Session("acme://letsencrypt.org/" + staging);

        account = Suppliers.memoize(() -> {
            Account account;
            Path accountJson = cacheDir.resolve("account.json");
            if (Files.exists(accountJson)) {
                LOGGER.info("Loading LetsEncrypt account.");
                try {
                    AccountJson json = JsonUtils.parse(GSON, accountJson, AccountJson.class);
                    Login login = session.login(
                            json.accountUrl,
                            KeyPairUtils.readKeyPair(new StringReader(String.join("\n", json.keystore)))
                    );
                    account = login.getAccount();
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to load account.", ex);
                }
            } else {
                LOGGER.info("Requesting new LetsEncrypt account.");
                try {
                    KeyPair keyPair = KeyPairUtils.createECKeyPair("secp256r1");
                    AccountBuilder builder = new AccountBuilder()
                            .agreeToTermsOfService()
                            .useKeyPair(keyPair);
                    if (!"no".equals(config.letsEncrypt.email)) {
                        builder.addEmail(config.letsEncrypt.email);
                    }
                    account = builder.create(session);

                    StringWriter sw = new StringWriter();
                    KeyPairUtils.writeKeyPair(keyPair, sw);
                    JsonUtils.write(GSON, IOUtils.makeParents(accountJson), new AccountJson(
                            account.getLocation(),
                            FastStream.of(sw.toString().split("\n"))
                                    .map(String::trim)
                                    .toList()
                    ));
                } catch (IOException | AcmeException ex) {
                    throw new RuntimeException("Failed to create account and save to disk.", ex);
                }
            }
            return account;
        });

        if (Files.exists(certsDir)) {
            try (Stream<Path> files = Files.list(certsDir)) {
                for (Path path : files.toList()) {
                    if (!path.toString().endsWith(".json")) continue;
                    CertInfo info = JsonUtils.parse(GSON, path, CertInfo.class);
                    certs.put(info.host, info);
                }
            } catch (IOException ex) {
                throw new RuntimeException("Failed to parse certs.", ex);
            }
        }
    }

    public boolean validate() {
        if (config.letsEncrypt.email == null) {
            LOGGER.error("LetsEncrypt email is not configured. Set to 'no' to disable.");
            return false;
        }
        return true;
    }

    public void setup() {
        account.get();
        setupDHParam();
    }

    public void expiryScan() {
        Instant nextWeek = Instant.now()
                .plus(7, ChronoUnit.DAYS);
        synchronized (certs) {
            for (CertInfo info : certs.values()) {
                if (pending.containsKey(info.host)) continue;
                if (!nextWeek.isAfter(info.expiresAt.toInstant())) continue;

                renewCertificate(info);
            }
        }
    }

    public CompletableFuture<CertInfo> getCertificates(String host) {
        return getCertificates(host, false);
    }

    public CompletableFuture<CertInfo> getCertificates(String host, boolean force) {
        CertInfo ret;
        if (!force) {
            ret = certs.get(host);
            if (ret != null) return CompletableFuture.completedFuture(ret);
        }

        synchronized (pending) {
            CompletableFuture<CertInfo> future = pending.get(host);
            if (future != null) return future;

            future = CompletableFuture.supplyAsync(() -> {
                CertInfo info;
                // Request the certificate.
                try {
                    info = requestCertificate(host);
                } catch (AcmeException | IOException ex) {
                    throw new RuntimeException("Failed to issue certificate", ex);
                }
                // Write our cache.
                try {
                    JsonUtils.write(GSON, certsDir.resolve(host + ".json"), info);
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to write cache.");
                }
                // Update global certs map.
                synchronized (certs) {
                    certs.put(host, info);
                }
                // Now we can nuke our future.
                synchronized (pending) {
                    pending.remove(host);
                }
                return info;
            }, EXECUTOR);
            pending.put(host, future);
            return future;
        }
    }

    private void renewCertificate(CertInfo info) {
        LOGGER.info("Certificate for {} is about to expire. Renewing..", info.host);
        getCertificates(info.host, true)
                .thenAcceptAsync(proxy.nginx::onRenewCertificates)
                .exceptionally(ex -> {
                    LOGGER.error("Failed to regen certificates for {}", info.host, ex);
                    return null;
                });
    }

    private CertInfo requestCertificate(String host) throws AcmeException, IOException {
        LOGGER.info("Ordering new certificate for {}", host);
        Order order = account.get().newOrder()
                .domain(host)
                .create();
        for (Authorization auth : order.getAuthorizations()) {
            boolean ret = handleDNSChallenge(auth, (Dns01Challenge) FastStream.of(auth.getChallenges())
                    .filter(e -> e.getType().equals(Dns01Challenge.TYPE))
                    .only());
            if (!ret) {
                throw new AcmeException("Failed Authorization.");
            }
        }
        LOGGER.info("Authorized! Processing order..");

        KeyPair domainKey = KeyPairUtils.createECKeyPair("secp256r1");
        CSRBuilder[] csrBuilder = { null };
        order.execute(domainKey, csr -> csrBuilder[0] = csr);
        int waitSteps = 20;
        try {
            while (order.getStatus() != Status.VALID && waitSteps-- > 0) {
                if (order.getStatus() == Status.INVALID) {
                    throw new AcmeException("Order Failed. " + order.getError().orElse(null));
                }

                Thread.sleep(TimeUnit.SECONDS.toMillis(3));
                order.update();
            }
            if (order.getStatus() != Status.VALID) {
                throw new AcmeException("Failed Order, timeout reached..");
            }
        } catch (InterruptedException ex) {
            throw new AcmeException("Interrupted whilst waiting for order.", ex);
        }
        Certificate certificate = order.getCertificate();
        LOGGER.info("Order succeeded!");

        Path dir = certsDir.resolve(host + "-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        Path csrFile = dir.resolve("domain.csr");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(csrFile), true)) {
            CryptoUtils.writePem(writer, csrBuilder[0].getEncoded(), "CERTIFICATE REQUEST");
        }

        Path privKeyFile = dir.resolve("privkey.pem");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(privKeyFile), true)) {
            CryptoUtils.writePem(writer, domainKey.getPrivate().getEncoded(), "PRIVATE KEY");
        }

        Path pubKeyFile = dir.resolve("pubkey.pem");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(pubKeyFile), true)) {
            CryptoUtils.writePem(writer, domainKey.getPublic().getEncoded(), "PUBLIC KEY");
        }

        List<X509Certificate> fullChain = certificate.getCertificateChain();
        X509Certificate cert = fullChain.get(0);

        Path certFile = dir.resolve("cert.pem");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(certFile), true)) {
            CryptoUtils.writePem(writer, cert.getEncoded(), "CERTIFICATE");
        } catch (CertificateEncodingException ex) {
            throw new RuntimeException("Failed to encode certificate?", ex);
        }

        Path chainFile = dir.resolve("chain.pem");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(chainFile), true)) {
            for (X509Certificate chainCert : fullChain) {
                if (chainCert == cert) continue;
                CryptoUtils.writePem(writer, chainCert.getEncoded(), "CERTIFICATE");
            }
        } catch (CertificateEncodingException ex) {
            throw new RuntimeException("Failed to encode certificate?", ex);
        }

        Path fullChainFile = dir.resolve("fullchain.pem");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(fullChainFile), true)) {
            for (X509Certificate chainCert : fullChain) {
                CryptoUtils.writePem(writer, chainCert.getEncoded(), "CERTIFICATE");
            }
        } catch (CertificateEncodingException ex) {
            throw new RuntimeException("Failed to encode certificate?", ex);
        }

        return new CertInfo(
                host,
                cert.getNotAfter(),
                csrFile,
                privKeyFile,
                pubKeyFile,
                certFile,
                chainFile,
                fullChainFile
        );
    }

    private boolean handleDNSChallenge(Authorization auth, Dns01Challenge challenge) throws AcmeException, IOException {
        LOGGER.info("Handling DNS Challenge.");
        if (challenge.getStatus() == Status.VALID) {
            LOGGER.info(" Already valid.");
            return true;
        }
        String domain = auth.getIdentifier().getDomain();
        CloudflareService.ZoneInfo zone = cloudflare.getZoneInfo(domain);
        LOGGER.info(" Creating CloudFlare DNS record..");
        CreateDNSRecordResponse dnsRecordResponse = cloudflare.createDNSRecord(zone, new RecordBuilder()
                .ttl(60)
                .name(Dns01Challenge.toRRName(domain))
                .content(challenge.getDigest())
                .type(TXT)
        );
        challenge.trigger();
        int waitSteps = 20;
        try {
            while (challenge.getStatus() != Status.VALID && waitSteps-- > 0) {
                if (challenge.getStatus() == Status.INVALID) {
                    LOGGER.error(" Failed DNS challenge. " + challenge.getError().orElse(null));
                    return false;
                }

                Thread.sleep(TimeUnit.SECONDS.toMillis(3));
                challenge.update();
            }
            if (challenge.getStatus() != Status.VALID) {
                LOGGER.error(" Failed DNS challenge. Timeout reached.");
            }
        } catch (InterruptedException ex) {
            LOGGER.error(" Interrupted whilst waiting for challenge.", ex);
            return false;
        }
        LOGGER.info("Cleaning up records..");
        for (DnsRecord dnsRecord : dnsRecordResponse.result) {
            cloudflare.deleteDNSRecord(zone, dnsRecord.id());
        }
        return true;
    }

    private void setupDHParam() {
        if (Files.exists(dhParam)) return;

        LOGGER.info("dhparam missing. Generating, this may take a few minutes...");

        DHParamGenerator generator = new DHParamGenerator(dhParam, config.letsEncrypt.dhParamBits);
        generator.startAndWait();
    }

    public record CertInfo(
            String host,
            Date expiresAt,
            @JsonAdapter (PathTypeAdapter.class) Path csr,
            @JsonAdapter (PathTypeAdapter.class) Path privKey,
            @JsonAdapter (PathTypeAdapter.class) Path pubKey,
            @JsonAdapter (PathTypeAdapter.class) Path cert,
            @JsonAdapter (PathTypeAdapter.class) Path chain,
            @JsonAdapter (PathTypeAdapter.class) Path fullChain
    ) {
    }

    private record AccountJson(
            URL accountUrl,
            List<String> keystore
    ) {
    }
}
