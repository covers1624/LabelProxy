package net.covers1624.lp.docker.data;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 2/11/23.
 */
public record ContainerSummary(
        @SerializedName ("Id") String id,
        @SerializedName ("Names") List<String> names,
        @SerializedName ("Image") String image,
        @SerializedName ("Created") Long created,
        @SerializedName ("Labels") Map<String, String> labels
) {

    public static final Type CONTAINER_LIST = new TypeToken<List<ContainerSummary>>() { }.getType();
}
