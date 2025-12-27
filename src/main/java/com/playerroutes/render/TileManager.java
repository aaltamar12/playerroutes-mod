package com.playerroutes.render;

import com.playerroutes.PlayerRoutes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class TileManager {
    private final Path tilesBasePath;
    private final MinecraftServer server;
    private final Set<String> renderedTiles = ConcurrentHashMap.newKeySet();
    private final Queue<TileTask> highPriorityQueue = new ConcurrentLinkedQueue<>();
    private final Queue<TileTask> lowPriorityQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> queuedTiles = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    // Config
    private static final int HIGH_PRIORITY_PER_TICK = 8;  // Chunks near players
    private static final int LOW_PRIORITY_PER_TICK = 4;   // Background chunks
    private static final int RENDER_INTERVAL_MS = 50;     // 20 times per second
    private static final int ADJACENT_RADIUS = 8;         // Chunks around player to prioritize (128 blocks)
    private static final int EXTENDED_RADIUS = 16;        // Extended render area (256 blocks)

    public TileManager(String basePath, MinecraftServer server) {
        this.tilesBasePath = Paths.get(basePath, "tiles");
        this.server = server;

        // Load existing tiles into cache
        loadExistingTiles();
    }

    private void loadExistingTiles() {
        try {
            if (Files.exists(tilesBasePath)) {
                Files.walk(tilesBasePath)
                        .filter(p -> p.toString().endsWith(".png"))
                        .forEach(p -> {
                            String relative = tilesBasePath.relativize(p).toString();
                            String key = relative.replace(".png", "").replace("/", ":");
                            renderedTiles.add(key);
                        });
                PlayerRoutes.LOGGER.info("Loaded {} existing tiles from cache", renderedTiles.size());
            }
        } catch (Exception e) {
            PlayerRoutes.LOGGER.warn("Failed to load existing tiles: {}", e.getMessage());
        }
    }

    public void start() {
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerRoutes-TileRenderer");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::processTileQueue,
                RENDER_INTERVAL_MS, RENDER_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Queue initial chunks around spawn and players
        queueInitialChunks();

        PlayerRoutes.LOGGER.info("TileManager started");
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        PlayerRoutes.LOGGER.info("TileManager stopped");
    }

    private void queueInitialChunks() {
        // Queue chunks around all players
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            queueChunksAroundPlayer(player);
        }
    }

    public void queueChunksAroundPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ChunkPos playerChunk = player.chunkPosition();
        String dimension = getDimensionName(level);

        // High priority: immediate area
        for (int dx = -ADJACENT_RADIUS; dx <= ADJACENT_RADIUS; dx++) {
            for (int dz = -ADJACENT_RADIUS; dz <= ADJACENT_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                queueTile(dimension, pos, true);
            }
        }

        // Low priority: extended area
        for (int dx = -EXTENDED_RADIUS; dx <= EXTENDED_RADIUS; dx++) {
            for (int dz = -EXTENDED_RADIUS; dz <= EXTENDED_RADIUS; dz++) {
                if (Math.abs(dx) > ADJACENT_RADIUS || Math.abs(dz) > ADJACENT_RADIUS) {
                    ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    queueTile(dimension, pos, false);
                }
            }
        }
    }

    public void queueTile(String dimension, ChunkPos pos, boolean highPriority) {
        String key = getTileKey(dimension, pos);

        // Skip if already rendered or queued
        if (renderedTiles.contains(key) || queuedTiles.contains(key)) {
            return;
        }

        queuedTiles.add(key);
        TileTask task = new TileTask(dimension, pos);

        if (highPriority) {
            highPriorityQueue.offer(task);
        } else {
            lowPriorityQueue.offer(task);
        }
    }

    public void onChunkModified(ServerLevel level, ChunkPos pos) {
        String dimension = getDimensionName(level);
        String key = getTileKey(dimension, pos);

        // Remove from rendered cache to force re-render
        renderedTiles.remove(key);

        // Queue for re-render
        queueTile(dimension, pos, true);
    }

    private void processTileQueue() {
        if (!running) return;

        // Process high priority first
        for (int i = 0; i < HIGH_PRIORITY_PER_TICK && !highPriorityQueue.isEmpty(); i++) {
            TileTask task = highPriorityQueue.poll();
            if (task != null) {
                processTask(task);
            }
        }

        // Then low priority
        for (int i = 0; i < LOW_PRIORITY_PER_TICK && !lowPriorityQueue.isEmpty(); i++) {
            TileTask task = lowPriorityQueue.poll();
            if (task != null) {
                processTask(task);
            }
        }
    }

    private void processTask(TileTask task) {
        String key = getTileKey(task.dimension, task.pos);
        queuedTiles.remove(key);

        // Must run on main server thread to access world data
        server.execute(() -> {
            try {
                ServerLevel level = getLevelByName(task.dimension);
                if (level == null) {
                    return;
                }

                Path tilePath = getTilePath(task.dimension, task.pos);
                boolean success = ChunkRenderer.renderChunk(level, task.pos, tilePath);

                if (success) {
                    renderedTiles.add(key);
                }
            } catch (Exception e) {
                PlayerRoutes.LOGGER.debug("Failed to process tile {}: {}", key, e.getMessage());
            }
        });
    }

    private String getTileKey(String dimension, ChunkPos pos) {
        return dimension + ":" + pos.x + ":" + pos.z;
    }

    private Path getTilePath(String dimension, ChunkPos pos) {
        return tilesBasePath.resolve(dimension).resolve(pos.x + "_" + pos.z + ".png");
    }

    private String getDimensionName(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        return key.location().getPath(); // "overworld", "the_nether", "the_end"
    }

    private ServerLevel getLevelByName(String name) {
        for (ServerLevel level : server.getAllLevels()) {
            if (getDimensionName(level).equals(name)) {
                return level;
            }
        }
        return null;
    }

    public boolean hasTile(String dimension, int chunkX, int chunkZ) {
        return renderedTiles.contains(dimension + ":" + chunkX + ":" + chunkZ);
    }

    public Path getTilesBasePath() {
        return tilesBasePath;
    }

    public int getRenderedCount() {
        return renderedTiles.size();
    }

    public int getQueueSize() {
        return highPriorityQueue.size() + lowPriorityQueue.size();
    }

    /**
     * Clear the rendered tiles cache to force re-rendering.
     * Does NOT delete files from disk, just clears the in-memory cache
     * so tiles will be re-rendered when requested.
     */
    public void clearRenderedCache() {
        int count = renderedTiles.size();
        renderedTiles.clear();
        queuedTiles.clear();
        highPriorityQueue.clear();
        lowPriorityQueue.clear();
        PlayerRoutes.LOGGER.info("Cleared tile cache ({} tiles), will re-render on demand", count);

        // Re-queue chunks around all players
        queueInitialChunks();
    }

    /**
     * Force re-render a specific dimension.
     */
    public void refreshDimension(String dimension) {
        // Remove all tiles for this dimension from cache
        renderedTiles.removeIf(key -> key.startsWith(dimension + ":"));
        PlayerRoutes.LOGGER.info("Cleared tile cache for dimension: {}", dimension);

        // Re-queue chunks around players in this dimension
        queueInitialChunks();
    }

    private static class TileTask {
        final String dimension;
        final ChunkPos pos;

        TileTask(String dimension, ChunkPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }
}
