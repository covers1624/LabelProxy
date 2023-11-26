package net.covers1624.lp.cloudflare.data.dns;

import com.google.gson.annotations.SerializedName;

import java.util.Date;
import java.util.List;

/**
 * Created by covers1624 on 27/11/23.
 */
public record DnsRecord(
        String content,
        String name,
        boolean proxied,
        RecordType type,
        String comment,
        @SerializedName ("created_on") Date createdOn,
        String id,
        boolean locked,
        RecordMeta meta,
        @SerializedName ("modified_on") Date modifiedOn,
        boolean proxiable,
        List<String> tags,
        int ttl,
        @SerializedName ("zone_id") String zoneId,
        @SerializedName ("zone_name") String zoneName
) {
}
