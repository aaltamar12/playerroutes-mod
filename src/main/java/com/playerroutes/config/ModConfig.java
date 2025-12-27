package com.playerroutes.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ModConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue SAMPLE_INTERVAL_MS;
    public static final ModConfigSpec.IntValue MIN_MOVE_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_IDLE_INTERVAL_MS;
    public static final ModConfigSpec.ConfigValue<String> STORAGE_PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> MONGO_URI;
    public static final ModConfigSpec.ConfigValue<String> JSON_DIR;
    public static final ModConfigSpec.IntValue WEBSOCKET_PORT;
    public static final ModConfigSpec.ConfigValue<String> WEBSOCKET_TOKEN;
    public static final ModConfigSpec.IntValue MAX_POINTS_PER_SESSION;
    public static final ModConfigSpec.IntValue WS_BATCH_INTERVAL_MS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("PlayerRoutes Configuration");
        builder.push("tracking");

        SAMPLE_INTERVAL_MS = builder
                .comment("Interval in milliseconds between position samples")
                .defineInRange("sampleIntervalMs", 2000, 500, 30000);

        MIN_MOVE_BLOCKS = builder
                .comment("Minimum blocks moved in XZ plane to record a new point")
                .defineInRange("minMoveBlocks", 2, 1, 50);

        MAX_IDLE_INTERVAL_MS = builder
                .comment("Maximum time in ms without recording a point (even if player hasn't moved)")
                .defineInRange("maxIdleIntervalMs", 10000, 5000, 60000);

        MAX_POINTS_PER_SESSION = builder
                .comment("Maximum points stored in memory per active session")
                .defineInRange("maxPointsPerSession", 5000, 100, 50000);

        builder.pop();
        builder.push("storage");

        STORAGE_PROVIDER = builder
                .comment("Storage provider: 'mongo' or 'json'")
                .define("provider", "json");

        MONGO_URI = builder
                .comment("MongoDB connection URI")
                .define("mongoUri", "mongodb://localhost:27017/playerroutes");

        JSON_DIR = builder
                .comment("Directory for JSON storage (relative to server root)")
                .define("jsonDir", "playerroutes-data");

        builder.pop();
        builder.push("websocket");

        WEBSOCKET_PORT = builder
                .comment("WebSocket server port")
                .defineInRange("port", 8765, 1024, 65535);

        WEBSOCKET_TOKEN = builder
                .comment("Authentication token for WebSocket connections")
                .define("token", "change-me-in-production");

        WS_BATCH_INTERVAL_MS = builder
                .comment("Interval for batching WebSocket updates")
                .defineInRange("batchIntervalMs", 500, 100, 2000);

        builder.pop();

        SPEC = builder.build();
    }
}
