package net.covers1624.lp.cloudflare.data.dns;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by covers1624 on 27/11/23.
 */
public class RecordBuilder {

    public @Nullable String content;
    public @Nullable String name;
    public boolean proxied;
    public @Nullable RecordType type;
    public @Nullable String comment;
    public List<String> tags = new ArrayList<>();
    public int ttl = 0;

    public RecordBuilder content(String content) {
        this.content = content;
        return this;
    }

    public RecordBuilder name(String name) {
        this.name = name;
        return this;
    }

    public RecordBuilder proxied() {
        proxied = true;
        return this;
    }

    public RecordBuilder type(RecordType type) {
        this.type = type;
        return this;
    }

    public RecordBuilder comment(String comment) {
        this.comment = comment;
        return this;
    }

    public RecordBuilder tag(String tag) {
        tags.add(tag);
        return this;
    }

    public RecordBuilder tags(List<String> tags) {
        this.tags.addAll(tags);
        return this;
    }

    public RecordBuilder tags(String... tags) {
        Collections.addAll(this.tags, tags);
        return this;
    }

    public RecordBuilder ttl(int ttl) {
        if (ttl < 60) throw new IllegalArgumentException("TTL must be higher than 60");
        if (ttl > 86400) throw new IllegalArgumentException("TTL must be lower than 86400");

        this.ttl = ttl;
        return this;
    }
}
