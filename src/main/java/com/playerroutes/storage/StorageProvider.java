package com.playerroutes.storage;

import com.playerroutes.data.PlayerSession;

import java.util.List;
import java.util.UUID;

public interface StorageProvider {
    void saveSession(PlayerSession session);

    PlayerSession getSession(String sessionId);

    List<PlayerSession> getSessionsByPlayer(UUID playerUuid, int limit, int offset);

    List<PlayerSession> getSessionsByTimeRange(long startTime, long endTime, int limit, int offset);

    List<PlayerSession> loadActiveSessions();

    List<PlayerSession> getAllSessions(int limit, int offset);

    long countSessions();

    long countSessionsByPlayer(UUID playerUuid);

    void close();
}
