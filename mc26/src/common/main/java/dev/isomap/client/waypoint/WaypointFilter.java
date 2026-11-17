package dev.isomap.client.waypoint;

import java.util.Collection;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Search over waypoints. Plain words match the name, {@code #word} matches tags; all terms must match.
 * Every tag in {@code requiredTags} must be present as well.
 */
public final class WaypointFilter {
    private WaypointFilter() {}

    public static boolean matches(Waypoint w, String query, Collection<String> requiredTags, @Nullable String dimension) {
        if (dimension != null && !dimension.equals(w.dimension)) {
            return false;
        }
        if (!w.tags.containsAll(requiredTags)) {
            return false;
        }
        String name = w.name.toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (term.isEmpty()) {
                continue;
            }
            if (term.startsWith("#")) {
                String tagTerm = term.substring(1);
                if (w.tags.stream().noneMatch(t -> t.contains(tagTerm))) {
                    return false;
                }
            } else if (!name.contains(term) && w.tags.stream().noneMatch(t -> t.contains(term))) {
                return false;
            }
        }
        return true;
    }
}
