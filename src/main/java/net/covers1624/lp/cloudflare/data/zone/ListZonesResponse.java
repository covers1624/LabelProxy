package net.covers1624.lp.cloudflare.data.zone;

import com.google.gson.annotations.SerializedName;
import net.covers1624.lp.cloudflare.data.CloudflareResponse;

import java.util.Date;
import java.util.List;

/**
 * Created by covers1624 on 6/11/23.
 */
public class ListZonesResponse extends CloudflareResponse<ListZonesResponse.Zone> {

    public record Zone(
            Account account,
            @SerializedName ("activated_on") Date activatedOn,
            @SerializedName ("created_on") Date createdOn,
            @SerializedName ("development_mode") int developmentMode,
            String id,
            Metadata meta,
            @SerializedName ("modified_on") Date modifiedOn,
            String name,
            @SerializedName ("original_dnshost") String originalDnsHost,
            @SerializedName ("original_name_servers") List<String> originalNameservers,
            @SerializedName ("original_registrar") String originalRegistrar,
            Owner owner,
            @SerializedName ("vanity_name_servers") List<String> vanityNameServers
    ) {
    }

    public record Account(String id, String name) {
    }

    public record Metadata(
            @SerializedName ("cdn_only") boolean cdnOnly,
            @SerializedName ("custom_certificate_quota") int customCertificateQuota,
            @SerializedName ("dns_only") boolean dnsOnly,
            @SerializedName ("foundation_dns") boolean foundationDns,
            @SerializedName ("page_rule_quota") int pageRuleQuota,
            @SerializedName ("phishing_detected") boolean phishingDetected,
            int step
    ) {
    }

    public record Owner(
            String id,
            String name,
            String type
    ) {
    }

}
