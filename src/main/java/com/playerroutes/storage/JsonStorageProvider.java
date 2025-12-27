package com.playerroutes.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playerroutes.PlayerRoutes;
import com.playerroutes.config.ModConfig;
import com.playerroutes.data.PlayerSession;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsonStorageProvider implements StorageProvider {
    private final Path baseDir;
    private final Gson gson;
    private final Map<String, PlayerSession> sessionCache = new ConcurrentHashMap<>();

    public JsonStorageProvider(String dirPath) {
        this.baseDir = Paths.get(dirPath);
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            Files.createDirectories(baseDir);
            loadAllSessions();
        } catch (IOException e) {
            PlayerRoutes.LOGGER.error("Failed to create JSON storage directory: {}", e.getMessage());
        }
    }

    private void loadAllSessions() {
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::loadSessionFile);
        } catch (IOException e) {
            PlayerRoutes.LOGGER.error("Failed to load sessions: {}", e.getMessage());
        }
    }

    private void loadSessionFile(Path filePath) {
        try (Reader reader = Files.newBufferedReader(filePath)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            PlayerSession session = PlayerSession.fromJson(json, ModConfig.MAX_POINTS_PER_SESSION.get());
            sessionCache.put(session.getSessionId(), session);
        } catch (Exception e) {
            PlayerRoutes.LOGGER.warn("Failed to load session file {}: {}", filePath, e.getMessage());
        }
    }

    @Override
    public void saveSession(PlayerSession session) {
        sessionCache.put(session.getSessionId(), session);

        Path filePath = baseDir.resolve(session.getSessionId() + ".json");
        try (Writer writer = Files.newBufferedWriter(filePath)) {
            gson.toJson(session.toJson(), writer);
        } catch (IOException e) {
            PlayerRoutes.LOGGER.error("Failed to save session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    @Override
    public PlayerSession getSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    @Override
    public List<PlayerSession> getSessionsByPlayer(UUID playerUuid, int limit, int offset) {
        return sessionCache.values().stream()
                .filter(s -> s.getPlayerUuid().equals(playerUuid))
                .sorted((a, b) -> Long.compare(b.getStartedAt(), a.getStartedAt()))
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<PlayerSession> getSessionsByTimeRange(long startTime, long endTime, int limit, int offset) {
        return sessionCache.values().stream()
                .filter(s -> s.getStartedAt() >= startTime && s.getStartedAt() <= endTime)
                .sorted((a, b) -> Long.compare(b.getStartedAt(), a.getStartedAt()))
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<PlayerSession> loadActiveSessions() {
        return sessionCache.values().stream()
                .filter(PlayerSession::isActive)
                .collect(Collectors.toList());
    }

    @Override
    public List<PlayerSession> getAllSessions(int limit, int offset) {
        return sessionCache.values().stream()
                .sorted((a, b) -> Long.compare(b.getStartedAt(), a.getStartedAt()))
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public long countSessions() {
        return sessionCache.size();
    }

    @Override
    public long countSessionsByPlayer(UUID playerUuid) {
        return sessionCache.values().stream()
                .filter(s -> s.getPlayerUuid().equals(playerUuid))
                .count();
    }

    @Override
    public void close() {
        // Save all sessions before closing
        for (PlayerSession session : sessionCache.values()) {
            saveSession(session);
        }
        sessionCache.clear();
    }
}
