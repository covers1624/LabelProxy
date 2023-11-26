package net.covers1624.lp.cloudflare.data.dns;

import net.covers1624.lp.cloudflare.data.CloudflareResponse;

/**
 * Created by covers1624 on 27/11/23.
 */
public class DeleteDnsRecordResponse extends CloudflareResponse<DeleteDnsRecordResponse.DeletedDNS> {

    public record DeletedDNS(String id) {
    }
}
