package com.playerroutes.data;

import com.google.gson.JsonObject;

public class SessionStats {
    private int samples;
    private double distanceXZ;

    public SessionStats() {
        this.samples = 0;
        this.distanceXZ = 0.0;
    }

    public SessionStats(int samples, double distanceXZ) {
        this.samples = samples;
        this.distanceXZ = distanceXZ;
    }

    public int getSamples() {
        return samples;
    }

    public double getDistanceXZ() {
        return distanceXZ;
    }

    public void incrementSamples() {
        samples++;
    }

    public void addDistance(double distance) {
        distanceXZ += distance;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("samples", samples);
        json.addProperty("distanceXZ", Math.round(distanceXZ * 10) / 10.0);
        return json;
    }

    public static SessionStats fromJson(JsonObject json) {
        return new SessionStats(
                json.get("samples").getAsInt(),
                json.get("distanceXZ").getAsDouble()
        );
    }
}
