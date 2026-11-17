package com.openclaw.android.core;

import org.json.JSONException;
import org.json.JSONObject;

/** 聊天记录条目。 */
public class ChatMessage {

    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;

    public int role = ROLE_USER;
    public String content = "";
    public String imagePath = null;  // 本地压缩图路径（可选）
    public String model = null;      // 产生该回复的模型名
    public boolean error = false;
    public long ts = System.currentTimeMillis();
    public int promptTokens = 0;
    public int completionTokens = 0;
    public int totalTokens = 0;
    public boolean estimated = false; // 服务端没返回 usage 时本地估算

    public ChatMessage() {
    }

    public ChatMessage(int role, String content) {
        this.role = role;
        this.content = content;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role);
            o.put("content", content);
            if (imagePath != null) o.put("img", imagePath);
            if (model != null) o.put("model", model);
            o.put("error", error);
            o.put("ts", ts);
            o.put("p", promptTokens);
            o.put("c", completionTokens);
            o.put("t", totalTokens);
            o.put("est", estimated);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.role = o.optInt("role", ROLE_USER);
        m.content = o.optString("content", "");
        m.imagePath = o.optString("img", null);
        m.model = o.has("model") && !o.isNull("model") ? o.optString("model", null) : null;
        m.error = o.optBoolean("error", false);
        m.ts = o.optLong("ts", System.currentTimeMillis());
        m.promptTokens = o.optInt("p", 0);
        m.completionTokens = o.optInt("c", 0);
        m.totalTokens = o.optInt("t", 0);
        m.estimated = o.optBoolean("est", false);
        return m;
    }
}
