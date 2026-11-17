package com.openclaw.android.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** 一条模型配置：自带协议、地址、密钥，方便把不同厂商排进同一条优先级链。 */
public class ModelConfig {

    public static final String P_OPENAI = "openai";
    public static final String P_ANTHROPIC = "anthropic";
    public static final String P_GEMINI = "gemini";

    public String id = UUID.randomUUID().toString();
    public String name = "新模型";
    public String protocol = P_OPENAI;
    public String baseUrl = "";
    public String apiKey = "";
    public String modelId = "";
    public boolean enabled = true;

    public ModelConfig() {
    }

    public ModelConfig(String name, String protocol, String baseUrl, String modelId) {
        this.name = name;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
    }

    public boolean ready() {
        return enabled
                && apiKey != null && apiKey.trim().length() > 0
                && modelId != null && modelId.trim().length() > 0
                && baseUrl != null && baseUrl.trim().length() > 0;
    }

    public String protocolLabel() {
        if (P_ANTHROPIC.equals(protocol)) return "Anthropic";
        if (P_GEMINI.equals(protocol)) return "Gemini";
        return "OpenAI 兼容";
    }

    public String maskedKey() {
        if (apiKey == null || apiKey.trim().length() == 0) return "未填写 API Key";
        String k = apiKey.trim();
        if (k.length() <= 8) return "••••";
        return k.substring(0, 4) + "••••" + k.substring(k.length() - 4);
    }

    public ModelConfig copy() {
        ModelConfig m = new ModelConfig();
        m.id = id;
        m.name = name;
        m.protocol = protocol;
        m.baseUrl = baseUrl;
        m.apiKey = apiKey;
        m.modelId = modelId;
        m.enabled = enabled;
        return m;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("protocol", protocol);
            o.put("baseUrl", baseUrl);
            o.put("apiKey", apiKey);
            o.put("modelId", modelId);
            o.put("enabled", enabled);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static ModelConfig fromJson(JSONObject o) {
        ModelConfig m = new ModelConfig();
        m.id = o.optString("id", UUID.randomUUID().toString());
        m.name = o.optString("name", "模型");
        m.protocol = o.optString("protocol", P_OPENAI);
        m.baseUrl = o.optString("baseUrl", "");
        m.apiKey = o.optString("apiKey", "");
        m.modelId = o.optString("modelId", "");
        m.enabled = o.optBoolean("enabled", true);
        return m;
    }
}
