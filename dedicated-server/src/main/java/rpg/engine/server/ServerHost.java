package rpg.engine.server;

import rpg.engine.runtime.*;
import rpg.engine.map.*;
import rpg.engine.core.component.*;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.network.*;
import rpg.engine.pak.PakStreamer;
import rpg.engine.world.InteractRequestedEvent;
import rpg.engine.script.UiSink;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless authoritative server host. Owns one {@link GameRuntime}, accepts TCP clients,
 * broadcasts {@link Snapshot}s, routes {@link Input} into the world and pushes UI events
 * ({@link UiLayout}) to the players that Lua scripts target.
 *
 * <p>Instance-based so it can be embedded either from the CLI ({@link ServerMain}), a Swing
 * admin UI or the Android/desktop clients' local server, with lifecycle controlled through
 * {@link #start}/{@link #stop}. Uses only Java 17 APIs so the same class runs on Android.
 */
public final class ServerHost {

    /**
     * What a server run needs. Map/pak paths are already resolved (see {@link ServerConfig}).
     *
     * @param tickHz world tick rate in Hz (≡ 1000/tickHz ms per tick); 0 or negative falls back to
     *               the {@link ServerConfig#DEFAULT_TICK_HZ default}, always clamped to 1..240
     */
    public record Config(Path map, List<Path> paks, int port, int tickHz, Path dataDir) {
        public Config {
            if (paks == null) paks = List.of();
            if (tickHz <= 0) tickHz = ServerConfig.DEFAULT_TICK_HZ;
            tickHz = Math.max(1, Math.min(240, tickHz));
        }
    }

    /** Receives human-readable operational messages (map load, listener address, pak list). */
    public interface Listener {
        void log(String line);
        default void error(String line) { log("[error] " + line); }
    }

    private final Listener listener;
    private final Map<Long, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, RegionState> regions = new ConcurrentHashMap<>();
    private final Map<String, Long> activeNames = new ConcurrentHashMap<>();
    private static final double MAX_PLAYER_SPEED = 6.0; // world tiles / second
    private static final int MAX_INPUT_ACTIONS_PER_SECOND = 24;
    /**
     * One thread per connected client. A cached pool (not Java-21 virtual threads, which are
     * unavailable on Android) keeps this class runnable inside the Android client, where the same
     * server code is embedded via {@code LocalServerBackend}.
     */
    private ExecutorService exec;
    private volatile GameRuntime runtime;
    private volatile boolean running;
    private ServerSocket server;
    private ScheduledExecutorService tick;
    private static final String PLAYER_SPRITE = rpg.engine.runtime.Sprites.PLAYER;
    private volatile List<Path> pakFiles = List.of();
    private volatile Path fontDir;
    /**
     * Serializes all access to {@link GameRuntime} (world mutate + snapshot broadcast). The ECS
     * is not thread-safe: world mutations come from the tick thread ({@code runtime.tick()},
     * Lua on_tick) and from per-client network threads ({@link #applyInput}, spawn/destroy,
     * dialog responses), while {@link #broadcastSnapshot} reads the whole world every tick.
     */
    private final Object worldLock = new Object();

    public ServerHost(Listener listener) {
        this.listener = listener == null ? line -> {} : listener;
    }

    /**
     * Moves the pre-host-layout player store into the host-owned data directory.
     * Existing files are preserved if the destination already contains a save.
     */
    private static void migrateLegacyPlayerStore(Path legacyDir, Path playerDir) {
        if (!Files.isDirectory(legacyDir)) {
            return;
        }
        try {
            Files.createDirectories(playerDir);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(legacyDir)) {
                for (Path file : files) {
                    Path target = playerDir.resolve(file.getFileName().toString());
                    if (Files.isRegularFile(file) && !Files.exists(target)) {
                        Files.move(file, target);
                    }
                }
            }
            try { Files.delete(legacyDir); } catch (DirectoryNotEmptyException ignored) { }
        } catch (IOException e) {
            System.err.println("[save] legacy player-store migration failed: " + e.getMessage());
        }
    }

    public boolean isRunning() { return running; }
    public GameRuntime runtime() { return runtime; }
    public int port() { return server == null ? -1 : server.getLocalPort(); }

    public synchronized void start(Config cfg) throws IOException {
        if (running) return;
        runtime = null;
        regions.clear();
        if (cfg.dataDir() != null) {
            Path hostDir = cfg.dataDir().resolve("host");
            Path playerDir = hostDir.resolve("players");
            migrateLegacyPlayerStore(cfg.dataDir().resolve("players"), playerDir);
            Files.createDirectories(playerDir);
        }
        pakFiles = List.copyOf(cfg.paks());
        fontDir = cfg.dataDir() != null
                ? cfg.dataDir().resolve("host").resolve("fonts")
                : (cfg.map() != null && cfg.map().getParent() != null ? cfg.map().getParent().resolve("fonts") : null);

        try {
            Path regionDir = cfg.dataDir() == null ? null : cfg.dataDir().resolve("host").resolve("regions");
            if (regionDir != null && Files.isDirectory(regionDir)) {
                loadRegions(regionDir);
            }
            if (regions.isEmpty() && cfg.map() != null && Files.isRegularFile(cfg.map())) {
                loadRegion("default", cfg.map(), 0);
            }
            if (regions.isEmpty()) listener.error("No region map configured");
            runtime = regions.values().stream().findFirst().map(r -> r.runtime).orElse(null);
            if (cfg.dataDir() != null) {
                FilePlayerStore store = new FilePlayerStore(cfg.dataDir().resolve("host").resolve("players"));
                for (RegionState region : regions.values()) region.runtime.setPlayerStore(store);
            }
        } catch (Exception e) {
            listener.error("Failed to load regions: " + e.getMessage());
        }
        if (!pakFiles.isEmpty()) {
            listener.log("Will stream " + pakFiles.size() + " pak(s): "
                    + pakFiles.stream().map(p -> p.getFileName().toString()).toList());
        }

        long tickMs = Math.max(1, Math.round(1000.0 / cfg.tickHz()));
        tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            try {
                synchronized (worldLock) {
                    // Host-owned knowledge (who is connected) is handed to the script layer each
                    // tick; Lua decides what to do with it (HP, HUD, quests — all script-side).
                    for (RegionState region : regions.values()) {
                        Set<Long> ids = new HashSet<>();
                        for (Client c : clients.values()) if (region.id.equals(c.regionId)) ids.add(c.entityId);
                        region.runtime.setPlayers(ids);
                        region.runtime.tick();
                    }
                    // Each region is simulated independently; clients only receive their current region.
                    broadcastSnapshot();
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }, 0, tickMs, TimeUnit.MILLISECONDS);
        listener.log("World tick @ " + cfg.tickHz() + " Hz (" + tickMs + " ms)");

        exec = Executors.newCachedThreadPool();
        server = new ServerSocket(cfg.port());
        server.setReuseAddress(true);
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "openrpg-dedicated-server");
        acceptor.setDaemon(true);
        acceptor.start();
        listener.log("RPG server listening on " + cfg.port());
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        exec.shutdownNow();
        if (tick != null) tick.shutdown();
        synchronized (worldLock) {
            for (Long id : new ArrayList<>(clients.keySet())) runtime.scripts().api().dispatchDisconnect(id);
        }
        clients.clear();
        listener.log("Server stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                exec.submit(() -> client(s));
            } catch (IOException e) {
                if (running) listener.error("accept error: " + e.getMessage());
            }
        }
    }

    private void client(Socket s) {
        Client client = null;
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Packet hello = Protocol.read(in);
            if (!(hello instanceof Hello h)) return;
            String playerName = normalizePlayerName(h.name());
            if (playerName == null) {
                Protocol.write(out, UiLayout.notify("Имя: 3–20 букв/цифр, пробелы и _-"));
                return;
            }
            String nameKey = playerName.toLowerCase(Locale.ROOT);
            if (activeNames.putIfAbsent(nameKey, -1L) != null) {
                Protocol.write(out, UiLayout.notify("Этот персонаж уже в игре."));
                return;
            }

            String startRegion = defaultRegionId();
            Long entityId = spawnPlayer(playerName, startRegion);
            activeNames.put(nameKey, entityId);
            client = new Client(entityId, startRegion, s, out);
            if (!pakFiles.isEmpty()) PakStreamer.send(out, pakFiles);
            Protocol.write(out, new Welcome(entityId));
            sendFonts(out, fontDir);
            // Register only after Welcome: the tick thread broadcasts snapshots to every client,
            // so a client must not be reachable before its handshake has completed.
            clients.put(entityId, client);
            // Stream the authoritative ground layer right after the handshake: the client never
            // reads a local .rmap — without this packet its floor stays empty.
            MapPacket mapPacket = mapPacket(startRegion);
            if (mapPacket != null) client.send(mapPacket);

            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (q instanceof Input x) {
                    applyInput(entityId, x);
                } else if (q instanceof DialogResponse r) {
                    // Deliver under the world lock: Lua dialogs must not race the tick thread.
                    synchronized (worldLock) { regionRuntime(client.regionId).respondDialog(r.dialogId(), r.choice()); }
                } else if (q instanceof Cmd c) {
                    // Custom command from a widget button / client script; value is host-owned.
                    synchronized (worldLock) { regionRuntime(client.regionId).dispatchCommand(entityId, c.code(), c.arg()); }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (client != null) {
                clients.remove(client.entityId);
                activeNames.remove(client.nameKey, client.entityId);
                client.dismiss();
                synchronized (worldLock) {
                    regionRuntime(client.regionId).scripts().api().dispatchDisconnect(client.entityId);
                    destroyPlayer(client.entityId, client.regionId);
                }
            }
        }
    }

    private Long spawnPlayer(String name, String regionId) {
        synchronized (worldLock) {
            GameRuntime r = regionRuntime(regionId);
            Long id = r.world().spawn().value();
            r.world().entities().set(new EntityId(id), new Name(name));
            WorldPosition spawn = r.scripts().api().spawnPoint();
            r.world().entities().set(new EntityId(id), new Transform(spawn, 0));
            r.world().entities().set(new EntityId(id), new CircleCollider(0.35));
            return id;
        }
    }

    private void destroyPlayer(Long id, String regionId) {
        synchronized (worldLock) { regionRuntime(regionId).world().entities().destroy(new EntityId(id)); }
    }

    private void applyInput(Long entityId, Input x) {
        synchronized (worldLock) {
            Client client = clients.get(entityId);
            if (client == null) return;
            GameRuntime r = regionRuntime(client.regionId);
            long now = System.nanoTime();
            double dt = client.lastInputNanos == 0 ? 0.05 : Math.min(0.10, Math.max(0.01, (now - client.lastInputNanos) / 1_000_000_000.0));
            client.lastInputNanos = now;
            double mag = Math.hypot(x.dx(), x.dy());
            if (mag > 1.0) { double k = 1.0 / mag; x = new Input(x.dx() * k, x.dy() * k, x.actions()); mag = 1.0; }
            if (mag > 0) {
                var e = new EntityId(entityId);
                var t = r.world().entities().get(e, Transform.class).orElseThrow();
                double step = MAX_PLAYER_SPEED * dt;
                var desired = new WorldPosition(t.position().x() + x.dx() * step, t.position().y() + x.dy() * step, t.position().elevation());
                var moved = r.world().collision().move(e, desired);
                r.world().entities().set(e, new Transform(moved, t.rotation()));
                MapPortal portal = r.map() == null ? null : r.map().portalAt(moved).orElse(null);
                if (portal != null) { transition(entityId, client.regionId, portal.targetRegion(), portal.targetPosition()); return; }
            }
            if (x.has(Input.INTERACT)) r.world().entities().get(new EntityId(entityId), Transform.class).ifPresent(t ->
                    r.world().interactTarget(t.position(), 2.0).ifPresent(target ->
                            r.world().events().emit(new InteractRequestedEvent(new EntityId(entityId), target))));
            if (x.has(Input.PRIMARY) && client.actionAllowed(now)) r.dispatchAction(entityId, "primary");
            if (x.has(Input.SECONDARY) && client.actionAllowed(now)) r.dispatchAction(entityId, "secondary");
            if (x.has(Input.INVENTORY) && client.actionAllowed(now)) r.dispatchAction(entityId, "inventory");
        }
    }

    private void transition(long entityId, String fromRegion, String targetRegion, WorldPosition targetPosition) {
        String target = targetRegion.toLowerCase(Locale.ROOT);
        RegionState destination = regions.get(target);
        Client client = clients.get(entityId);
        if (destination == null || client == null || fromRegion.equals(target)) return;
        GameRuntime source = regionRuntime(fromRegion);
        GameRuntime dest = destination.runtime;
        var old = source.world().entities();
        var id = new EntityId(entityId);
        Name name = old.get(id, Name.class).orElse(null);
        if (name == null) return;
        old.destroy(id);
        dest.world().entities().adopt(id);
        dest.world().entities().set(id, name);
        dest.world().entities().set(id, new Transform(targetPosition, 0));
        dest.world().entities().set(id, new CircleCollider(0.35));
        client.regionId = target;
        client.send(mapPacket(target));
        client.send(UiLayout.notify("Регион: " + destination.map.name()));
    }

    private String defaultRegionId() {
        if (regions.containsKey("default")) return "default";
        return regions.keySet().stream().sorted().findFirst().orElseThrow(() -> new IllegalStateException("no regions"));
    }

    private GameRuntime regionRuntime(String id) {
        RegionState r = regions.get(id.toLowerCase(Locale.ROOT));
        if (r == null) throw new IllegalArgumentException("unknown region: " + id);
        return r.runtime;
    }

    private void loadRegions(Path dir) throws IOException {
        RegionCatalog catalog = RegionCatalog.load(dir);
        int index = 1;
        for (RegionCatalog.Entry entry : catalog.all()) loadRegion(entry.id(), entry.path(), index++);
        listener.log("Loaded " + regions.size() + " region(s) from " + dir);
    }

    private void loadRegion(String id, Path map, int index) throws IOException {
        GameRuntime r = new GameRuntime(((long) index) << 48);
        r.setUiSink(uiSink());
        r.setRegionTransitionSink(this::transitionFromScript);
        r.loadMap(map);
        regions.put(id.toLowerCase(Locale.ROOT), new RegionState(id.toLowerCase(Locale.ROOT), map, r));
        if (runtime == null) runtime = r;
        listener.log("Loaded region " + id + ": " + map.getFileName());
    }

    private void transitionFromScript(long playerEntityId, String targetRegion, WorldPosition targetPosition) {
        Client c = clients.get(playerEntityId);
        if (c != null) transition(playerEntityId, c.regionId, targetRegion, targetPosition);
    }

    private final class RegionState {
        final String id; final Path map; final GameRuntime runtime;
        RegionState(String id, Path map, GameRuntime runtime) { this.id = id; this.map = map; this.runtime = runtime; }
    }

    private static String normalizePlayerName(String raw) {
        if (raw == null) return null;
        String n = raw.trim().replaceAll("\\s+", " ");
        int cp = n.codePointCount(0, n.length());
        if (cp < 3 || cp > 20) return null;
        if (!n.matches("[\\p{L}\\p{N}_\\- ]+")) return null;
        return n;
    }

    private void broadcastSnapshot() {
        final double viewRadius = 24.0;
        for (Client viewer : clients.values()) {
            GameRuntime r = regionRuntime(viewer.regionId);
            EntityId viewerId = new EntityId(viewer.entityId);
            var viewerTransform = r.world().entities().get(viewerId, Transform.class).orElse(null);
            if (viewerTransform == null) continue;
            final double vx = viewerTransform.position().x(), vy = viewerTransform.position().y();
            List<Snapshot.EntityState> states = r.world().entities().entities().stream().map(id -> {
                var reg = r.world().entities();
                var t = reg.get(id, Transform.class).orElse(null);
                if (t == null) return null;
                double dx = t.position().x() - vx, dy = t.position().y() - vy;
                if (!id.equals(viewerId) && dx * dx + dy * dy > viewRadius * viewRadius) return null;
                String prefab = reg.get(id, Prefab.class).map(Prefab::value).orElse(null);
                String sprite = (prefab == null || prefab.isBlank()) ? PLAYER_SPRITE : prefab;
                double scale = reg.get(id, Scale.class).map(Scale::value).orElse(1.0);
                return new Snapshot.EntityState(id.value(), t.position().x(), t.position().y(), t.position().elevation(), sprite, scale);
            }).filter(Objects::nonNull).toList();
            viewer.send(new Snapshot(states));
        }
    }

    /** The authoritative ground layer to stream on connect; null if the host has no map. */

    private void sendFonts(OutputStream out, Path dir) {
        if (dir == null) return;
        if (!Files.isDirectory(dir)) return;
        try (var paths = Files.list(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                String n = p.getFileName().toString();
                String lower = n.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) continue;
                byte[] data = Files.readAllBytes(p);
                if (data.length > (1 << 19)) { listener.error("Font too large, skipped: " + n); continue; }
                Protocol.write(out, new Font(n, data));
            }
        } catch (IOException e) { listener.error("Font streaming failed: " + e.getMessage()); }
    }

    private MapPacket mapPacket(String regionId) {
        GameRuntime r = regionRuntime(regionId);
        if (r == null || r.map() == null || r.map().layers().isEmpty()) return null;
        var ground = r.map().layers().get(0);
        return new MapPacket(ground.width(), ground.height(), ground.tiles());
    }

    private final class Client {
        final long entityId;
        volatile String regionId;
        final String nameKey;
        final OutputStream out;
        volatile boolean dismissed;
        volatile long lastInputNanos;
        volatile long actionWindowNanos;
        volatile int actionsInWindow;
        Client(long entityId, String regionId, Socket s, OutputStream out) {
            this.entityId = entityId;
            this.regionId = regionId;
            this.nameKey = runtimeName(entityId);
            this.out = out;
        }
        private String runtimeName(long id) {
            return ServerHost.this.regionRuntime(regionId).world().entities().get(new EntityId(id), Name.class).map(Name::value).orElse("").toLowerCase(Locale.ROOT);
        }
        synchronized boolean actionAllowed(long now) {
            if (now - actionWindowNanos >= 1_000_000_000L) { actionWindowNanos = now; actionsInWindow = 0; }
            if (actionsInWindow >= MAX_INPUT_ACTIONS_PER_SECOND) return false;
            actionsInWindow++;
            return true;
        }
        synchronized void send(Packet p) {
            if (dismissed) return;
            try { Protocol.write(out, p); } catch (IOException ignored) { dismissed = true; }
        }
        void dismiss() { dismissed = true; }
    }
}