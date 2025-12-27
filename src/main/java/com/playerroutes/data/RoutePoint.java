package com.playerroutes.data;

import com.google.gson.JsonObject;

public record RoutePoint(
        long timestamp,
        double x,
        double y,
        double z,
        String dimension
) {
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("t", timestamp);
        json.addProperty("x", Math.round(x * 10) / 10.0);
        json.addProperty("y", Math.round(y * 10) / 10.0);
        json.addProperty("z", Math.round(z * 10) / 10.0);
        json.addProperty("dim", dimension);
        return json;
    }

    public static RoutePoint fromJson(JsonObject json) {
        return new RoutePoint(
                json.get("t").getAsLong(),
                json.get("x").getAsDouble(),
                json.get("y").getAsDouble(),
                json.get("z").getAsDouble(),
                json.get("dim").getAsString()
        );
    }

    public double distanceXZ(RoutePoint other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
