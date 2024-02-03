package net.covers1624.lp.docker.data;

import com.google.gson.annotations.SerializedName;
import net.covers1624.quack.collection.FastStream;

import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 2/11/23.
 */
public record DockerContainer(
        @SerializedName ("Id") String id,
        @SerializedName ("Config") Config config,
        @SerializedName ("NetworkSettings") NetworkSettings networkSettings
) {

    public record Config(
            @SerializedName ("Labels") Map<String, String> labels
    ) {

        public boolean hasLabelWithPrefix(String prefix) {
            for (String s : labels.keySet()) {
                if (s.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        public Map<String, String> getLabels(String prefix) {
            return FastStream.of(labels.entrySet())
                    .filter(e->e.getKey().startsWith(prefix))
                    .toLinkedHashMap(Map.Entry::getKey, Map.Entry::getValue);
        }
    }

    public record NetworkSettings(
            @SerializedName ("Networks") Map<String, Network> networks
    ) {
    }

    public record Network(
            @SerializedName ("Aliases") List<String> aliases,
            @SerializedName ("NetworkId") String networkId,
            @SerializedName ("EndpointId") String endpointId,
            @SerializedName ("Gateway") String gateway,
            @SerializedName ("IPAddress") String ipAddress
    ) {
    }
}
