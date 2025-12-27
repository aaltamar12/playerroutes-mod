package com.playerroutes.session;

import com.playerroutes.PlayerRoutes;
import com.playerroutes.config.ModConfig;
import com.playerroutes.data.PlayerSession;
import com.playerroutes.data.RoutePoint;
import com.playerroutes.render.TileManager;
import com.playerroutes.storage.StorageProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public class SessionManager {
    private final StorageProvider storageProvider;
    private final int sampleIntervalMs;
    private final int minMoveBlocks;
    private final TileManager tileManager;
    private final Map<UUID, PlayerSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSampleTime = new ConcurrentHashMap<>();
    private final Map<UUID, RoutePoint> lastRecordedPoint = new ConcurrentHashMap<>();
    private MinecraftServer server;
    private ScheduledExecutorService scheduler;
    private int tickCounter = 0;
    private final int ticksPerSample;
    private int tileUpdateCounter = 0;
    private static final int TICKS_PER_TILE_UPDATE = 40; // Every 2 seconds
    private int timeUpdateCounter = 0;
    private static final int TICKS_PER_TIME_UPDATE = 100; // Every 5 seconds

    public SessionManager(StorageProvider storageProvider, int sampleIntervalMs, int minMoveBlocks, TileManager tileManager) {
        this.storageProvider = storageProvider;
        this.sampleIntervalMs = sampleIntervalMs;
        this.minMoveBlocks = minMoveBlocks;
        this.tileManager = tileManager;
        this.ticksPerSample = Math.max(1, sampleIntervalMs / 50); // 50ms per tick
    }

    public void start(MinecraftServer server) {
        this.server = server;
        NeoForge.EVENT_BUS.register(this);

        // Load any active sessions that weren't properly closed
        storageProvider.loadActiveSessions().forEach(session -> {
            // Mark old active sessions as ended (server was restarted)
            session.endSession();
            storageProvider.saveSession(session);
        });

        PlayerRoutes.LOGGER.info("SessionManager started");
    }

    public void stop() {
        NeoForge.EVENT_BUS.unregister(this);

        // End all active sessions
        for (PlayerSession session : activeSessions.values()) {
            session.endSession();
            storageProvider.saveSession(session);
            broadcastSessionEnd(session);
        }
        activeSessions.clear();
        lastSampleTime.clear();
        lastRecordedPoint.clear();

        if (scheduler != null) {
            scheduler.shutdown();
        }

        PlayerRoutes.LOGGER.info("SessionManager stopped");
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        String name = player.getName().getString();
        String sessionId = generateSessionId();

        PlayerSession session = new PlayerSession(
                sessionId,
                uuid,
                name,
                ModConfig.MAX_POINTS_PER_SESSION.get()
        );

        // Add initial position
        RoutePoint initialPoint = createRoutePoint(player);
        session.addPoint(initialPoint);
        lastRecordedPoint.put(uuid, initialPoint);
        lastSampleTime.put(uuid, System.currentTimeMillis());

        activeSessions.put(uuid, session);
        storageProvider.saveSession(session);

        // Queue tiles around player
        if (tileManager != null) {
            tileManager.queueChunksAroundPlayer(player);
        }

        PlayerRoutes.LOGGER.info("Started session {} for player {}", sessionId, name);
        broadcastSessionStart(session);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        PlayerSession session = activeSessions.remove(uuid);

        if (session != null) {
            session.endSession();
            storageProvider.saveSession(session);
            PlayerRoutes.LOGGER.info("Ended session {} for player {}", session.getSessionId(), session.getPlayerName());
            broadcastSessionEnd(session);
        }

        lastSampleTime.remove(uuid);
        lastRecordedPoint.remove(uuid);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        tileUpdateCounter++;
        timeUpdateCounter++;

        // Update tiles around players periodically
        if (tileUpdateCounter >= TICKS_PER_TILE_UPDATE) {
            tileUpdateCounter = 0;
            if (tileManager != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    tileManager.queueChunksAroundPlayer(player);
                }
            }
        }

        // Broadcast world time periodically
        if (timeUpdateCounter >= TICKS_PER_TIME_UPDATE) {
            timeUpdateCounter = 0;
            var wsServer = PlayerRoutes.getInstance().getWebSocketServer();
            if (wsServer != null) {
                wsServer.broadcastWorldTime();
            }
        }

        if (tickCounter < ticksPerSample) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        int maxIdleInterval = ModConfig.MAX_IDLE_INTERVAL_MS.get();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            PlayerSession session = activeSessions.get(uuid);

            if (session == null) continue;

            // Update ping
            session.updatePing(player.connection.latency());

            RoutePoint currentPoint = createRoutePoint(player);
            RoutePoint lastPoint = lastRecordedPoint.get(uuid);
            Long lastTime = lastSampleTime.get(uuid);

            boolean shouldRecord = false;
            String reason = "";

            if (lastPoint == null) {
                shouldRecord = true;
                reason = "first point";
            } else {
                double distance = currentPoint.distanceXZ(lastPoint);
                boolean dimensionChanged = !currentPoint.dimension().equals(lastPoint.dimension());
                long timeSinceLastRecord = lastTime != null ? now - lastTime : maxIdleInterval;

                if (distance >= minMoveBlocks) {
                    shouldRecord = true;
                    reason = "moved " + String.format("%.1f", distance) + " blocks";
                } else if (dimensionChanged) {
                    shouldRecord = true;
                    reason = "dimension changed";
                } else if (timeSinceLastRecord >= maxIdleInterval) {
                    shouldRecord = true;
                    reason = "idle timeout";
                }
            }

            if (shouldRecord) {
                session.addPoint(currentPoint);
                lastRecordedPoint.put(uuid, currentPoint);
                lastSampleTime.put(uuid, now);
                broadcastRoutePoint(session, currentPoint, player);
            }
        }
    }

    private RoutePoint createRoutePoint(ServerPlayer player) {
        return new RoutePoint(
                System.currentTimeMillis(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.level().dimension().location().toString()
        );
    }

    private String generateSessionId() {
        return "sess_" + Long.toString(System.currentTimeMillis(), 36) + "_" +
                Long.toString((long) (Math.random() * 1_000_000), 36);
    }

    private void broadcastSessionStart(PlayerSession session) {
        var wsServer = PlayerRoutes.getInstance().getWebSocketServer();
        if (wsServer != null) {
            wsServer.broadcastSessionStart(session);
        }
    }

    private void broadcastSessionEnd(PlayerSession session) {
        var wsServer = PlayerRoutes.getInstance().getWebSocketServer();
        if (wsServer != null) {
            wsServer.broadcastSessionEnd(session);
        }
    }

    private void broadcastRoutePoint(PlayerSession session, RoutePoint point, ServerPlayer player) {
        var wsServer = PlayerRoutes.getInstance().getWebSocketServer();
        if (wsServer != null) {
            long worldTime = player.level().getDayTime() % 24000; // 0-24000 ticks in a day
            wsServer.broadcastRoutePoint(session, point, worldTime);
        }
    }

    public Collection<PlayerSession> getActiveSessions() {
        return activeSessions.values();
    }

    public PlayerSession getSession(String sessionId) {
        for (PlayerSession session : activeSessions.values()) {
            if (session.getSessionId().equals(sessionId)) {
                return session;
            }
        }
        return storageProvider.getSession(sessionId);
    }

    public PlayerSession getActiveSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }
}
