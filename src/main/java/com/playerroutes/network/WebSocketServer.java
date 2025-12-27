package com.playerroutes.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playerroutes.PlayerRoutes;
import com.playerroutes.data.PlayerSession;
import com.playerroutes.data.RoutePoint;
import com.playerroutes.render.TileManager;
import com.playerroutes.session.SessionManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketServer extends org.java_websocket.server.WebSocketServer {
    private final String authToken;
    private final SessionManager sessionManager;
    private final MinecraftServer server;
    private final Set<WebSocket> authenticatedClients = ConcurrentHashMap.newKeySet();

    public WebSocketServer(int port, String authToken, SessionManager sessionManager, MinecraftServer server) {
        super(new InetSocketAddress(port));
        this.authToken = authToken;
        this.sessionManager = sessionManager;
        this.server = server;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String token = handshake.getFieldValue("Authorization");
        if (token == null || token.isEmpty()) {
            // Check query parameter as fallback
            String resourceDesc = handshake.getResourceDescriptor();
            if (resourceDesc.contains("token=")) {
                int start = resourceDesc.indexOf("token=") + 6;
                int end = resourceDesc.indexOf("&", start);
                token = end > 0 ? resourceDesc.substring(start, end) : resourceDesc.substring(start);
            }
        } else if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // Debug: log received vs expected token
        PlayerRoutes.LOGGER.info("Token received: '{}', expected: '{}'", token, authToken);

        if (authToken.equals(token)) {
            authenticatedClients.add(conn);
            PlayerRoutes.LOGGER.info("WebSocket client connected and authenticated: {}", conn.getRemoteSocketAddress());
            sendInitialState(conn);
        } else {
            PlayerRoutes.LOGGER.warn("WebSocket client rejected (invalid token): {}", conn.getRemoteSocketAddress());
            conn.close(4001, "Invalid authentication token");
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        authenticatedClients.remove(conn);
        PlayerRoutes.LOGGER.info("WebSocket client disconnected: {} (code: {}, reason: {})",
                conn.getRemoteSocketAddress(), code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!authenticatedClients.contains(conn)) {
            PlayerRoutes.LOGGER.warn("Ignoring message from unauthenticated client: {}", conn.getRemoteSocketAddress());
            return; // Ignore messages from unauthenticated clients
        }

        PlayerRoutes.LOGGER.info("Received message from {}: {}", conn.getRemoteSocketAddress(), message);

        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";

            PlayerRoutes.LOGGER.info("Message type: {}", type);

            switch (type) {
                case "refresh_tiles":
                    handleRefreshTiles(conn, json);
                    break;
                case "teleport":
                    PlayerRoutes.LOGGER.info("Handling teleport request");
                    handleTeleport(conn, json);
                    break;
                case "execute_command":
                    handleExecuteCommand(conn, json);
                    break;
                default:
                    PlayerRoutes.LOGGER.warn("Unknown message type from {}: {}", conn.getRemoteSocketAddress(), type);
            }
        } catch (Exception e) {
            PlayerRoutes.LOGGER.error("Failed to parse message from {}: {}", conn.getRemoteSocketAddress(), e.getMessage(), e);
        }
    }

    private void handleRefreshTiles(WebSocket conn, JsonObject json) {
        TileManager tileManager = PlayerRoutes.getInstance().getTileManager();
        if (tileManager == null) {
            sendError(conn, "TileManager not available");
            return;
        }

        String dimension = json.has("dimension") ? json.get("dimension").getAsString() : null;

        if (dimension != null && !dimension.isEmpty()) {
            // Refresh specific dimension
            tileManager.refreshDimension(dimension);
            sendSuccess(conn, "Refreshing tiles for dimension: " + dimension);
        } else {
            // Refresh all dimensions
            tileManager.clearRenderedCache();
            sendSuccess(conn, "Refreshing all tiles");
        }
    }

    private void sendSuccess(WebSocket conn, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "refresh_tiles_response");
        response.addProperty("success", true);
        response.addProperty("message", message);
        conn.send(response.toString());
    }

    private void sendError(WebSocket conn, String error) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "refresh_tiles_response");
        response.addProperty("success", false);
        response.addProperty("error", error);
        conn.send(response.toString());
    }

    private void handleTeleport(WebSocket conn, JsonObject json) {
        PlayerRoutes.LOGGER.info("handleTeleport called, server={}", server);
        if (server == null) {
            PlayerRoutes.LOGGER.warn("Server is null, cannot teleport");
            sendCommandResponse(conn, false, "Server not available");
            return;
        }

        String playerName = json.has("player") ? json.get("player").getAsString() : null;
        String targetPlayer = json.has("targetPlayer") ? json.get("targetPlayer").getAsString() : null;

        Double x = json.has("x") ? json.get("x").getAsDouble() : null;
        Double y = json.has("y") ? json.get("y").getAsDouble() : null;
        Double z = json.has("z") ? json.get("z").getAsDouble() : null;

        PlayerRoutes.LOGGER.info("Teleport params: player={}, targetPlayer={}, x={}, y={}, z={}", playerName, targetPlayer, x, y, z);

        if (playerName == null || playerName.isEmpty()) {
            sendCommandResponse(conn, false, "Player name required");
            return;
        }

        String command;
        if (targetPlayer != null && !targetPlayer.isEmpty()) {
            command = String.format("tp %s %s", playerName, targetPlayer);
        } else if (x != null && y != null && z != null) {
            command = String.format("tp %s %.2f %.2f %.2f", playerName, x, y, z);
        } else {
            sendCommandResponse(conn, false, "Either target player or coordinates required");
            return;
        }

        PlayerRoutes.LOGGER.info("Executing command: /{}", command);
        executeServerCommand(conn, command);
    }

    private void handleExecuteCommand(WebSocket conn, JsonObject json) {
        if (server == null) {
            sendCommandResponse(conn, false, "Server not available");
            return;
        }

        String command = json.has("command") ? json.get("command").getAsString() : null;
        if (command == null || command.isEmpty()) {
            sendCommandResponse(conn, false, "Command required");
            return;
        }

        // Remove leading slash if present
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        executeServerCommand(conn, command);
    }

    private void executeServerCommand(WebSocket conn, String command) {
        try {
            // Execute on main server thread
            server.execute(() -> {
                try {
                    var commandSource = server.createCommandSourceStack();
                    server.getCommands().performPrefixedCommand(commandSource, command);
                    sendCommandResponse(conn, true, "Command executed: /" + command);
                    PlayerRoutes.LOGGER.info("WebSocket executed command: /{}", command);
                } catch (Exception e) {
                    sendCommandResponse(conn, false, "Error: " + e.getMessage());
                    PlayerRoutes.LOGGER.error("Failed to execute command: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            sendCommandResponse(conn, false, "Error: " + e.getMessage());
        }
    }

    private void sendCommandResponse(WebSocket conn, boolean success, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "command_response");
        response.addProperty("success", success);
        response.addProperty("message", message);
        conn.send(response.toString());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        PlayerRoutes.LOGGER.error("WebSocket error: {}", ex.getMessage());
        if (conn != null) {
            authenticatedClients.remove(conn);
        }
    }

    @Override
    public void onStart() {
        PlayerRoutes.LOGGER.info("WebSocket server started on port {}", getPort());
    }

    private void sendInitialState(WebSocket conn) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "init");

        JsonArray sessionsArray = new JsonArray();
        for (PlayerSession session : sessionManager.getActiveSessions()) {
            sessionsArray.add(session.toJson());
        }
        message.add("activeSessions", sessionsArray);

        // Add current world time from overworld
        if (server != null) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                long worldTime = overworld.getDayTime() % 24000;
                message.addProperty("worldTime", worldTime);
            }
        }

        conn.send(message.toString());
    }

    public void broadcastSessionStart(PlayerSession session) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "session_start");
        message.add("session", session.toJson());
        broadcastToAuthenticated(message.toString());
    }

    public void broadcastSessionEnd(PlayerSession session) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "session_end");
        message.addProperty("sessionId", session.getSessionId());
        message.addProperty("playerUuid", session.getPlayerUuid().toString());
        message.addProperty("playerName", session.getPlayerName());
        message.addProperty("endedAt", session.getEndedAt());
        message.add("stats", session.getStats().toJson());
        broadcastToAuthenticated(message.toString());
    }

    public void broadcastWorldTime() {
        if (server == null || authenticatedClients.isEmpty()) return;

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        long worldTime = overworld.getDayTime() % 24000;
        JsonObject message = new JsonObject();
        message.addProperty("type", "time_update");
        message.addProperty("worldTime", worldTime);
        broadcastToAuthenticated(message.toString());
    }

    public void broadcastRoutePoint(PlayerSession session, RoutePoint point, long worldTime) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "route_point");
        message.addProperty("sessionId", session.getSessionId());
        message.addProperty("playerUuid", session.getPlayerUuid().toString());
        message.addProperty("playerName", session.getPlayerName());
        message.add("point", point.toJson());
        message.addProperty("worldTime", worldTime); // 0-24000 ticks

        JsonObject conn = new JsonObject();
        conn.addProperty("online", session.isActive());
        conn.addProperty("pingMs", session.getPingMs());
        message.add("conn", conn);

        broadcastToAuthenticated(message.toString());
    }

    private void broadcastToAuthenticated(String message) {
        for (WebSocket client : authenticatedClients) {
            if (client.isOpen()) {
                client.send(message);
            }
        }
    }
}
