package com.playerroutes;

import com.playerroutes.config.ModConfig;
import com.playerroutes.network.WebSocketServer;
import com.playerroutes.render.TileManager;
import com.playerroutes.session.SessionManager;
import com.playerroutes.storage.StorageProvider;
import com.playerroutes.storage.JsonStorageProvider;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PlayerRoutes.MODID)
public class PlayerRoutes {
    public static final String MODID = "playerroutes";
    public static final Logger LOGGER = LoggerFactory.getLogger(PlayerRoutes.class);

    private static PlayerRoutes instance;
    private StorageProvider storageProvider;
    private SessionManager sessionManager;
    private WebSocketServer webSocketServer;
    private TileManager tileManager;

    public PlayerRoutes(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;

        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(Type.SERVER, ModConfig.SPEC);

        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("PlayerRoutes mod initializing...");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("PlayerRoutes: Server starting, initializing components...");

        // Initialize storage provider (JSON only for now)
        storageProvider = new JsonStorageProvider(ModConfig.JSON_DIR.get());
        LOGGER.info("Using JSON storage provider");

        // Initialize tile manager for map rendering
        tileManager = new TileManager(ModConfig.JSON_DIR.get(), event.getServer());
        tileManager.start();
        LOGGER.info("TileManager started for map rendering");

        // Initialize session manager
        sessionManager = new SessionManager(
                storageProvider,
                ModConfig.SAMPLE_INTERVAL_MS.get(),
                ModConfig.MIN_MOVE_BLOCKS.get(),
                tileManager
        );
        sessionManager.start(event.getServer());

        // Initialize WebSocket server
        int wsPort = ModConfig.WEBSOCKET_PORT.get();
        String wsToken = ModConfig.WEBSOCKET_TOKEN.get();
        webSocketServer = new WebSocketServer(wsPort, wsToken, sessionManager, event.getServer());
        try {
            webSocketServer.start();
            LOGGER.info("WebSocket server started on port {}", wsPort);
        } catch (Exception e) {
            LOGGER.error("Failed to start WebSocket server: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("PlayerRoutes: Server stopping, cleaning up...");

        if (sessionManager != null) {
            sessionManager.stop();
        }

        if (tileManager != null) {
            tileManager.stop();
        }

        if (webSocketServer != null) {
            try {
                webSocketServer.stop(1000);
            } catch (InterruptedException e) {
                LOGGER.warn("WebSocket server stop interrupted");
            }
        }

        if (storageProvider != null) {
            storageProvider.close();
        }
    }

    public static PlayerRoutes getInstance() {
        return instance;
    }

    public StorageProvider getStorageProvider() {
        return storageProvider;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public WebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public TileManager getTileManager() {
        return tileManager;
    }
}
