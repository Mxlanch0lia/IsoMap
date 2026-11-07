package dev.isomap.client.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A saved location. Plain fields because the class is the JSON schema of the per-world waypoint file. */
public final class Waypoint {
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_TAG_LENGTH = 24;
    public static final int MAX_TAGS = 16;
    public static final String DEATH_TAG = "death";

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public int x;
    public int y;
    public int z;
    public String dimension = "minecraft:overworld";
    public int color = 0x6FB1FF;
    public boolean visible = true;
    public boolean death;
    public long createdAt = System.currentTimeMillis();
    public List<String> tags = new ArrayList<>();

    public Waypoint copy() {
        Waypoint c = new Waypoint();
        c.id = id;
        c.name = name;
        c.x = x;
        c.y = y;
        c.z = z;
        c.dimension = dimension;
        c.color = color;
        c.visible = visible;
        c.death = death;
        c.createdAt = createdAt;
        c.tags = new ArrayList<>(tags);
        return c;
    }

    /** Tags are compared case-insensitively and may not contain spaces, so that {@code #tag} search stays unambiguous. */
    public static String normalizeTag(String raw) {
        String tag = raw.trim().toLowerCase(Locale.ROOT).replaceAll("^#+", "").replaceAll("\\s+", "-");
        return tag.length() > MAX_TAG_LENGTH ? tag.substring(0, MAX_TAG_LENGTH) : tag;
    }

    public boolean addTag(String raw) {
        String tag = normalizeTag(raw);
        if (tag.isEmpty() || tags.size() >= MAX_TAGS || tags.contains(tag)) {
            return false;
        }
        tags.add(tag);
        return true;
    }

    public boolean removeTag(String tag) {
        return tags.remove(tag);
    }

    /** Repairs values a hand-edited or older file might contain. Returns false if the entry is unusable. */
    boolean sanitize() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (name == null) {
            name = "";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        if (dimension == null || dimension.isBlank()) {
            return false;
        }
        color &= 0xFFFFFF;
        List<String> clean = new ArrayList<>();
        if (tags != null) {
            for (String raw : tags) {
                String tag = raw == null ? "" : normalizeTag(raw);
                if (!tag.isEmpty() && !clean.contains(tag) && clean.size() < MAX_TAGS) {
                    clean.add(tag);
                }
            }
        }
        tags = clean;
        return true;
    }
}
