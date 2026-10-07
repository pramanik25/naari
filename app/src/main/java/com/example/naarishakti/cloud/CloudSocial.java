package com.example.naarishakti.cloud;

import android.content.Context;
import android.location.Location;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * API calls for the "together" features: family circle, community safety map, anonymous
 * community and helper stats (CONTRACT.md "v1.2 additions").
 *
 * <p>Unlike the SOS paths nothing here is queued: these are screens she is looking at, so every
 * method is a plain blocking call that throws {@link CloudException}. Call off the main thread.
 */
public final class CloudSocial {

    private CloudSocial() {}

    /** True when an API server is configured and she has not deleted her cloud data. */
    public static boolean isActive(Context ctx) {
        return Cloud.isActive(ctx);
    }

    // ================================================================== family circle

    public static final class Member {
        public String userId;
        public String name;
        /** What this person is to her: "guardian", "ward" or "both". */
        public String relation;
        /** False while this person is not sharing a location. */
        public boolean hasLocation;
        public double lat;
        public double lng;
        /** Battery percent, or -1 when unknown. */
        public int battery = -1;
        public long updatedAt;
    }

    public static final class Circle {
        /** Whether the server currently holds her own position. */
        public boolean sharing;
        public final List<Member> members = new ArrayList<>();
    }

    public static Circle circle(Context ctx) throws CloudException {
        JsonObject res = ApiClient.get(ctx).call("GET", "/api/v1/circle", null);
        Circle out = new Circle();
        out.sharing = Json.bool(res, "sharing", false);
        for (JsonObject m : objects(Json.arr(res, "members"))) {
            Member member = new Member();
            member.userId = Json.str(m, "userId");
            member.name = Json.str(m, "name");
            member.relation = Json.str(m, "relation");
            JsonObject loc = m.has("location") && m.get("location").isJsonObject()
                    ? m.getAsJsonObject("location") : null;
            if (loc != null) {
                member.hasLocation = true;
                member.lat = Json.dbl(loc, "lat", 0);
                member.lng = Json.dbl(loc, "lng", 0);
                member.battery = (int) Json.lng(loc, "battery", -1);
                member.updatedAt = Json.lng(loc, "updatedAt", 0);
            }
            if (member.userId != null) out.members.add(member);
        }
        return out;
    }

    /** Uploads her position for the people she is linked to. {@code battery} is 0-100 or -1. */
    static void shareLocation(Context ctx, Location loc, int battery) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("sharing", true);
        body.addProperty("lat", loc.getLatitude());
        body.addProperty("lng", loc.getLongitude());
        if (loc.hasAccuracy()) body.addProperty("accuracy", loc.getAccuracy());
        if (battery >= 0 && battery <= 100) body.addProperty("battery", battery);
        ApiClient.get(ctx).call("PUT", "/api/v1/circle/me", body);
    }

    // ================================================================== safety map

    public static final String CAT_POORLY_LIT = "poorly_lit";
    public static final String CAT_ISOLATED = "isolated";
    public static final String CAT_HARASSMENT = "harassment";
    public static final String CAT_TRANSPORT = "unsafe_transport";
    public static final String CAT_SAFE_SPOT = "safe_spot";

    public static final class PlaceReport {
        public String id;
        public String category;
        public double lat;
        public double lng;
        public String note;
        public long createdAt;
        public boolean mine;
        public int distanceM;
    }

    public static List<PlaceReport> placeReports(Context ctx, double lat, double lng, int radiusM)
            throws CloudException {
        String path = String.format(Locale.US, "/api/v1/places/reports?lat=%.6f&lng=%.6f&radius=%d",
                lat, lng, radiusM);
        JsonObject res = ApiClient.get(ctx).call("GET", path, null);
        List<PlaceReport> out = new ArrayList<>();
        for (JsonObject r : objects(Json.arr(res, "reports"))) {
            PlaceReport p = new PlaceReport();
            p.id = Json.str(r, "id");
            p.category = Json.str(r, "category");
            p.lat = Json.dbl(r, "lat", 0);
            p.lng = Json.dbl(r, "lng", 0);
            p.note = orEmpty(Json.str(r, "note"));
            p.createdAt = Json.lng(r, "createdAt", 0);
            p.mine = Json.bool(r, "mine", false);
            p.distanceM = (int) Json.lng(r, "distanceM", 0);
            if (p.id != null && p.category != null) out.add(p);
        }
        return out;
    }

    public static void addPlaceReport(Context ctx, String category, double lat, double lng,
                                      @Nullable String note) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("lat", lat);
        body.addProperty("lng", lng);
        body.addProperty("note", orEmpty(note));
        ApiClient.get(ctx).call("POST", "/api/v1/places/reports", body);
    }

    public static void deletePlaceReport(Context ctx, String id) throws CloudException {
        ApiClient.get(ctx).call("DELETE", "/api/v1/places/reports/" + Uri.encode(id), null);
    }

    public static void flagPlaceReport(Context ctx, String id) throws CloudException {
        ApiClient.get(ctx).call("POST", "/api/v1/places/reports/" + Uri.encode(id) + "/flag", null);
    }

    // ================================================================== community

    public static final class Post {
        public String id;
        public String topic;
        public String body;
        public String alias;
        public int replyCount;
        public long createdAt;
        public boolean mine;
    }

    public static final class Reply {
        public String id;
        public String body;
        public String alias;
        public long createdAt;
        public boolean mine;
        /** Written by the person who started the thread. */
        public boolean byAuthor;
    }

    public static final class Feed {
        public final List<Post> posts = new ArrayList<>();
        /** Pass to {@link #feed} to load older posts; 0 when there are none. */
        public long nextBefore;
    }

    public static final class PostThread {
        public Post post;
        public final List<Reply> replies = new ArrayList<>();
    }

    /** One page of the feed. {@code topic} null = every topic; {@code before} 0 = newest. */
    public static Feed feed(Context ctx, @Nullable String topic, long before) throws CloudException {
        StringBuilder path = new StringBuilder("/api/v1/community/posts");
        char sep = '?';
        if (topic != null) {
            path.append(sep).append("topic=").append(Uri.encode(topic));
            sep = '&';
        }
        if (before > 0) path.append(sep).append("before=").append(before);
        JsonObject res = ApiClient.get(ctx).call("GET", path.toString(), null);
        Feed out = new Feed();
        for (JsonObject p : objects(Json.arr(res, "posts"))) {
            Post post = post(p);
            if (post != null) out.posts.add(post);
        }
        out.nextBefore = Json.lng(res, "nextBefore", 0);
        return out;
    }

    public static Post createPost(Context ctx, String topic, String text) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("topic", topic);
        body.addProperty("body", text);
        Post post = post(ApiClient.get(ctx).call("POST", "/api/v1/community/posts", body));
        if (post == null) throw new CloudException.BadResponse("create post: missing id");
        return post;
    }

    public static PostThread thread(Context ctx, String postId) throws CloudException {
        JsonObject res = ApiClient.get(ctx).call("GET", postPath(postId), null);
        PostThread out = new PostThread();
        out.post = res.has("post") && res.get("post").isJsonObject() ? post(res.getAsJsonObject("post")) : null;
        if (out.post == null) throw new CloudException.BadResponse("thread: missing post");
        for (JsonObject r : objects(Json.arr(res, "replies"))) {
            Reply reply = reply(r);
            if (reply != null) out.replies.add(reply);
        }
        return out;
    }

    public static Reply reply(Context ctx, String postId, String text) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("body", text);
        Reply reply = reply(ApiClient.get(ctx).call("POST", postPath(postId) + "/replies", body));
        if (reply == null) throw new CloudException.BadResponse("reply: missing id");
        return reply;
    }

    public static void deletePost(Context ctx, String postId) throws CloudException {
        ApiClient.get(ctx).call("DELETE", postPath(postId), null);
    }

    public static void deleteReply(Context ctx, String replyId) throws CloudException {
        ApiClient.get(ctx).call("DELETE", replyPath(replyId), null);
    }

    /** Reports a post ({@code isPost}) or a reply to the moderators' auto-hide count. */
    public static void flag(Context ctx, boolean isPost, String id) throws CloudException {
        ApiClient.get(ctx).call("POST", (isPost ? postPath(id) : replyPath(id)) + "/flag", null);
    }

    /** Hides everything written by the author of this post or reply from her. */
    public static void block(Context ctx, boolean isPost, String id) throws CloudException {
        ApiClient.get(ctx).call("POST", (isPost ? postPath(id) : replyPath(id)) + "/block", null);
    }

    // ================================================================== helper stats

    /** {alerted, responded}: how often she was asked to help nearby and how often she answered. */
    public static int[] helperStats(Context ctx) throws CloudException {
        JsonObject res = ApiClient.get(ctx).call("GET", "/api/v1/helper/stats", null);
        return new int[]{(int) Json.lng(res, "alerted", 0), (int) Json.lng(res, "responded", 0)};
    }

    // ------------------------------------------------------------------ parsing

    @Nullable
    private static Post post(@Nullable JsonObject p) {
        if (p == null || Json.str(p, "id") == null) return null;
        Post post = new Post();
        post.id = Json.str(p, "id");
        post.topic = orEmpty(Json.str(p, "topic"));
        post.body = orEmpty(Json.str(p, "body"));
        post.alias = orEmpty(Json.str(p, "alias"));
        post.replyCount = (int) Json.lng(p, "replyCount", 0);
        post.createdAt = Json.lng(p, "createdAt", 0);
        post.mine = Json.bool(p, "mine", false);
        return post;
    }

    @Nullable
    private static Reply reply(@Nullable JsonObject r) {
        if (r == null || Json.str(r, "id") == null) return null;
        Reply reply = new Reply();
        reply.id = Json.str(r, "id");
        reply.body = orEmpty(Json.str(r, "body"));
        reply.alias = orEmpty(Json.str(r, "alias"));
        reply.createdAt = Json.lng(r, "createdAt", 0);
        reply.mine = Json.bool(r, "mine", false);
        reply.byAuthor = Json.bool(r, "byAuthor", false);
        return reply;
    }

    private static List<JsonObject> objects(@Nullable JsonArray arr) {
        List<JsonObject> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement e : arr) if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
        }
        return out;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String postPath(String id) {
        return "/api/v1/community/posts/" + Uri.encode(id);
    }

    private static String replyPath(String id) {
        return "/api/v1/community/replies/" + Uri.encode(id);
    }
}
