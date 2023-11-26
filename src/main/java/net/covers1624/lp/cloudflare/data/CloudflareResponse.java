package net.covers1624.lp.cloudflare.data;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import net.covers1624.quack.collection.FastStream;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 6/11/23.
 */
public abstract class CloudflareResponse<T> {

    public boolean success = true; // Default to true for responses which have slim bodies for success.
    public List<Error> errors = new ArrayList<>();
    public List<Message> messages = new ArrayList<>();
    public @Nullable ResultInfo resultInfo;
    @JsonAdapter (ListOrSingleDeserializer.class)
    public List<T> result = new ArrayList<>();

    public record Error(long code, String message) {

        @Override
        public String toString() {
            return "(" + code + ") " + message;
        }
    }

    public record Message(long code, String message) { }

    public record ResultInfo(
            int count,
            int page,
            @SerializedName ("per_page") int perPage,
            @SerializedName ("total_count") int totalCount
    ) {
    }

    public String errorsToString() {
        return FastStream.of(errors).join("\n");
    }

    // TODO, The cloudflare API docs are unclear if this can actually occur for a singular request.
    //       I suspect it probably can't as it would complicate object modeling it, like we are here.
    //       However, as of writing the DNSCreate response _may_ exhibit this behaviour.
    public static class ListOrSingleDeserializer implements JsonDeserializer<List<?>> {

        @Override
        public List<?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            // If it's an object, wrap it in an array.
            if (json.isJsonObject()) {
                JsonArray array = new JsonArray();
                array.add(json);
                json = array;
            }
            return context.deserialize(json, typeOfT);
        }
    }
}
