package net.covers1624.lp.cloudflare;

import com.google.gson.Gson;
import net.covers1624.lp.Config;
import net.covers1624.lp.Config.CloudflareAuth;
import net.covers1624.lp.LabelProxy;
import net.covers1624.lp.cloudflare.data.CloudflareResponse;
import net.covers1624.lp.cloudflare.data.dns.CreateDNSRecordResponse;
import net.covers1624.lp.cloudflare.data.dns.DeleteDnsRecordResponse;
import net.covers1624.lp.cloudflare.data.dns.ListDNSRecordsResponse;
import net.covers1624.lp.cloudflare.data.dns.RecordBuilder;
import net.covers1624.lp.cloudflare.data.zone.ListZonesResponse;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.net.httpapi.EngineRequest;
import net.covers1624.quack.net.httpapi.EngineResponse;
import net.covers1624.quack.net.httpapi.WebBody;
import net.covers1624.quack.net.httpapi.curl4j.Curl4jHttpEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 6/11/23.
 */
public class CloudflareService {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Gson GSON = new Gson();

    private final Config config;
    private final Curl4jHttpEngine httpEngine;

    private final Map<String, ZoneInfo> zones = new HashMap<>();

    public CloudflareService(LabelProxy proxy, Curl4jHttpEngine httpEngine) {
        this.config = proxy.config;
        this.httpEngine = httpEngine;
    }

    public boolean validate() {
        if (config.cloudflareAuths.isEmpty()) {
            LOGGER.error("No CloudFlare auths are configured.");
            return false;
        }

        return true;
    }

    public ListZonesResponse listZones(CloudflareAuth auth) throws IOException {
        EngineRequest request = httpEngine.newRequest()
                .method("GET", null)
                .url("https://api.cloudflare.com/client/v4/zones");
        return executeRequest(auth, request, ListZonesResponse.class);
    }

    public ListDNSRecordsResponse getDnsRecords(ZoneInfo info) throws IOException {
        EngineRequest request = httpEngine.newRequest()
                .method("GET", null)
                .url("https://api.cloudflare.com/client/v4/zones/" + info.zone.id() + "/dns_records");
        return executeRequest(info.auth, request, ListDNSRecordsResponse.class);
    }

    public CreateDNSRecordResponse createDNSRecord(ZoneInfo info, RecordBuilder record) throws IOException {
        EngineRequest request = httpEngine.newRequest()
                .method("POST", WebBody.string(GSON.toJson(record), "application/json"))
                .url("https://api.cloudflare.com/client/v4/zones/" + info.zone.id() + "/dns_records");
        return executeRequest(info.auth, request, CreateDNSRecordResponse.class);
    }

    public DeleteDnsRecordResponse deleteDNSRecord(ZoneInfo info, String identifier) throws IOException {
        EngineRequest request = httpEngine.newRequest()
                .method("DELETE", null)
                .url("https://api.cloudflare.com/client/v4/zones/" + info.zone.id() + "/dns_records/" + identifier);
        return executeRequest(info.auth, request, DeleteDnsRecordResponse.class);
    }

    private <T extends CloudflareResponse<?>> T executeRequest(CloudflareAuth auth, EngineRequest request, Class<T> rClass) throws IOException {
        addCFAuth(auth, request);
        T resp;
        try (EngineResponse response = request.execute()) {
            WebBody body = response.body();
            if (body == null) throw new IOException("Expected http body. Response code: " + response.statusCode());

            try (InputStream is = body.open()) {
                resp = JsonUtils.parse(GSON, is, rClass);
            }
        }
        if (!resp.success) {
            throw new IOException("Request failed.\n" + resp.errorsToString());
        }
        return resp;
    }

    private void addCFAuth(CloudflareAuth auth, EngineRequest request) {
        if (auth.serviceAuthKey != null) {
            request.header("X-Auth-User-Service-key", auth.serviceAuthKey);
        } else {
            request.header("X-Auth-Key", requireNonNull(auth.key));
            request.header("X-Auth-Email", requireNonNull(auth.email));
        }
    }

    public ZoneInfo getZoneInfo(String zone) {
        synchronized (zones) {
            ZoneInfo info = FastStream.of(zones.entrySet())
                    .filter(e -> zone.endsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .onlyOrDefault();
            if (info != null) return info;
            pollZones();
            info = FastStream.of(zones.entrySet())
                    .filter(e -> zone.endsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .onlyOrDefault();
            if (info != null) return info;

            throw new RuntimeException("Zone does not exist on any configured accounts: " + zone);
        }
    }

    private void pollZones() {
        zones.clear();
        for (CloudflareAuth auth : config.cloudflareAuths) {
            try {
                ListZonesResponse resp = listZones(auth);
                for (ListZonesResponse.Zone zone : resp.result) {
                    zones.put(zone.name(), new ZoneInfo(zone, auth));
                }
            } catch (IOException ex) {
                throw new RuntimeException("Failed to list zones.", ex);
            }
        }
    }

    public record ZoneInfo(ListZonesResponse.Zone zone, Config.CloudflareAuth auth) { }
}
