package com.example.naarishakti.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Null-safe accessors for Gson trees (server fields may be missing or null). */
final class Json {

    private Json() {}

    @Nullable
    static JsonObject parseObject(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            JsonElement e = JsonParser.parseString(text);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Nullable
    static String str(@Nullable JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        try {
            String s = e.getAsString();
            return s == null || s.isEmpty() ? null : s;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static double dbl(@Nullable JsonObject o, String key, double def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static long lng(@Nullable JsonObject o, String key, long def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            try {
                return (long) e.getAsDouble();
            } catch (RuntimeException ex2) {
                return def;
            }
        }
    }

    static boolean bool(@Nullable JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static boolean has(@Nullable JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    @Nullable
    static JsonArray arr(@Nullable JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}
