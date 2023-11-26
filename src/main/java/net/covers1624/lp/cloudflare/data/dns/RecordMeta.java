package net.covers1624.lp.cloudflare.data.dns;

import com.google.gson.annotations.SerializedName;

/**
 * Created by covers1624 on 27/11/23.
 */
public record RecordMeta(
        @SerializedName ("auto_added") boolean autoAdded,
        String source
) { }
