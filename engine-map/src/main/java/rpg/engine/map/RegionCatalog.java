package rpg.engine.map;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Discovers immutable map regions from a directory. The file stem is the stable region id. */
public final class RegionCatalog {
    private final Map<String, Entry> entries;

    public record Entry(String id, Path path, RMap map) {}

    private RegionCatalog(Map<String, Entry> entries) { this.entries = Map.copyOf(entries); }

    public static RegionCatalog load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("region directory not found: " + directory);
        Map<String, Entry> out = new LinkedHashMap<>();
        try (var stream = Files.list(directory)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rmap")).sorted().toList()) {
                String file = path.getFileName().toString();
                String id = file.substring(0, file.length() - 5).toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IOException("invalid region id: " + id);
                if (out.containsKey(id)) throw new IOException("duplicate region id: " + id);
                out.put(id, new Entry(id, path, RMapIO.read(path)));
            }
        }
        for (Entry e : out.values()) {
            for (MapPortal portal : e.map().portals()) {
                if (!out.containsKey(portal.targetRegion().toLowerCase(Locale.ROOT)))
                    throw new IOException("region " + e.id() + " portal " + portal.id() + " targets missing region " + portal.targetRegion());
            }
        }
        return new RegionCatalog(out);
    }

    public Optional<Entry> find(String id) { return Optional.ofNullable(entries.get(id.toLowerCase(Locale.ROOT))); }
    public Collection<Entry> all() { return entries.values(); }
    public int size() { return entries.size(); }
}
