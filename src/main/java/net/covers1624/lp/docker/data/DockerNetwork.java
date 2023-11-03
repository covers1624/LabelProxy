package net.covers1624.lp.docker.data;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * Created by covers1624 on 2/11/23.
 */
public record DockerNetwork(
        @SerializedName ("Name") String name,
        @SerializedName ("Id") String id,
        @SerializedName ("Scope") String scope,
        @SerializedName ("Driver") String driver,
        @SerializedName ("Labels") Map<String, String> labels
) {

}
