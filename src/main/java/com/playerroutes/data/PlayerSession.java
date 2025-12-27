package com.playerroutes.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerSession {
    private final String sessionId;
    private final UUID playerUuid;
    private final String playerName;
    private final long startedAt;
    private Long endedAt;
    private boolean active;
    private long lastSeenAt;
    private int pingMs;
    private final SessionStats stats;
    private final List<RoutePoint> path;
    private final int maxPoints;

    public PlayerSession(String sessionId, UUID playerUuid, String playerName, int maxPoints) {
        this.sessionId = sessionId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.startedAt = System.currentTimeMillis();
        this.endedAt = null;
        this.active = true;
        this.lastSeenAt = this.startedAt;
        this.pingMs = 0;
        this.stats = new SessionStats();
        this.path = new ArrayList<>();
        this.maxPoints = maxPoints;
    }

    // Constructor for loading from storage
    public PlayerSession(String sessionId, UUID playerUuid, String playerName,
                         long startedAt, Long endedAt, boolean active, long lastSeenAt,
                         SessionStats stats, List<RoutePoint> path, int maxPoints) {
        this.sessionId = sessionId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.active = active;
        this.lastSeenAt = lastSeenAt;
        this.pingMs = 0;
        this.stats = stats;
        this.path = new ArrayList<>(path);
        this.maxPoints = maxPoints;
    }

    public void addPoint(RoutePoint point) {
        if (!path.isEmpty() && path.size() >= maxPoints) {
            // Remove oldest points to make room (keep last 80%)
            int toRemove = maxPoints / 5;
            path.subList(0, toRemove).clear();
        }

        if (!path.isEmpty()) {
            RoutePoint lastPoint = path.get(path.size() - 1);
            stats.addDistance(point.distanceXZ(lastPoint));
        }

        path.add(point);
        stats.incrementSamples();
        lastSeenAt = point.timestamp();
    }

    public void endSession() {
        this.active = false;
        this.endedAt = System.currentTimeMillis();
        this.lastSeenAt = this.endedAt;
    }

    public void updatePing(int pingMs) {
        this.pingMs = pingMs;
    }

    public String getSessionId() {
        return sessionId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public boolean isActive() {
        return active;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public int getPingMs() {
        return pingMs;
    }

    public SessionStats getStats() {
        return stats;
    }

    public List<RoutePoint> getPath() {
        return path;
    }

    public RoutePoint getLastPoint() {
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("_id", sessionId);
        json.addProperty("playerUuid", playerUuid.toString());
        json.addProperty("playerName", playerName);
        json.addProperty("startedAt", startedAt);
        if (endedAt != null) {
            json.addProperty("endedAt", endedAt);
        }
        json.addProperty("active", active);
        json.addProperty("lastSeenAt", lastSeenAt);
        json.add("stats", stats.toJson());

        JsonArray pathArray = new JsonArray();
        for (RoutePoint point : path) {
            pathArray.add(point.toJson());
        }
        json.add("path", pathArray);

        return json;
    }

    public JsonObject toSummaryJson() {
        JsonObject json = new JsonObject();
        json.addProperty("sessionId", sessionId);
        json.addProperty("playerUuid", playerUuid.toString());
        json.addProperty("playerName", playerName);
        json.addProperty("startedAt", startedAt);
        if (endedAt != null) {
            json.addProperty("endedAt", endedAt);
        }
        json.addProperty("active", active);
        json.addProperty("lastSeenAt", lastSeenAt);
        json.add("stats", stats.toJson());

        RoutePoint lastPoint = getLastPoint();
        if (lastPoint != null) {
            json.add("lastPoint", lastPoint.toJson());
        }

        JsonObject conn = new JsonObject();
        conn.addProperty("online", active);
        conn.addProperty("pingMs", pingMs);
        json.add("conn", conn);

        return json;
    }

    public static PlayerSession fromJson(JsonObject json, int maxPoints) {
        String sessionId = json.get("_id").getAsString();
        UUID playerUuid = UUID.fromString(json.get("playerUuid").getAsString());
        String playerName = json.get("playerName").getAsString();
        long startedAt = json.get("startedAt").getAsLong();
        Long endedAt = json.has("endedAt") && !json.get("endedAt").isJsonNull()
                ? json.get("endedAt").getAsLong() : null;
        boolean active = json.get("active").getAsBoolean();
        long lastSeenAt = json.get("lastSeenAt").getAsLong();
        SessionStats stats = SessionStats.fromJson(json.getAsJsonObject("stats"));

        List<RoutePoint> path = new ArrayList<>();
        JsonArray pathArray = json.getAsJsonArray("path");
        for (int i = 0; i < pathArray.size(); i++) {
            path.add(RoutePoint.fromJson(pathArray.get(i).getAsJsonObject()));
        }

        return new PlayerSession(sessionId, playerUuid, playerName, startedAt, endedAt,
                active, lastSeenAt, stats, path, maxPoints);
    }
}
