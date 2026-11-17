package com.openclaw.android.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 全部本地数据：模型链、设置、聊天记录、用量统计。 */
public class Store {

    private static final String PREF = "openclaw_prefs";
    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 模型

    private static final int SEED_VERSION = 2;

    public List<ModelConfig> models() {
        String raw = sp.getString("models", null);
        List<ModelConfig> list = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(ModelConfig.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {
            }
        }
        boolean changed = false;
        if (list.isEmpty()) {
            list.addAll(Defaults.models());
            changed = true;
        } else if (sp.getInt("seedVersion", 0) < SEED_VERSION) {
            // 老版本升级：补上内置模型，不动用户自己的
            for (ModelConfig d : Defaults.models()) {
                boolean exists = false;
                for (ModelConfig m : list) {
                    if (m.modelId.equals(d.modelId) && m.baseUrl.equals(d.baseUrl)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    list.add(d);
                    changed = true;
                }
            }
        }
        if (sp.getInt("seedVersion", 0) < SEED_VERSION) {
            sp.edit().putInt("seedVersion", SEED_VERSION).apply();
        }
        if (changed) saveModels(list);
        return list;
    }

    public void saveModels(List<ModelConfig> models) {
        JSONArray arr = new JSONArray();
        for (ModelConfig m : models) arr.put(m.toJson());
        sp.edit().putString("models", arr.toString()).apply();
    }

    /** 按顺序返回「可用」的模型，即调用链。 */
    public List<ModelConfig> chain() {
        List<ModelConfig> out = new ArrayList<>();
        for (ModelConfig m : models()) {
            if (m.ready()) out.add(m);
        }
        return out;
    }

    /** 链上第一个模型名，用于标题栏展示。 */
    public String primaryModelName() {
        List<ModelConfig> list = chain();
        return list.isEmpty() ? "未配置模型" : list.get(0).name;
    }

    // ------------------------------------------------------------------ 设置

    public Settings settings() {
        Settings s = new Settings();
        String raw = sp.getString("settings", null);
        if (raw == null) {
            sp.edit().putBoolean("settingsV2", true).apply();
            return s;
        }
        try {
            JSONObject o = new JSONObject(raw);
            s.systemPrompt = o.optString("systemPrompt", s.systemPrompt);
            s.temperature = o.optDouble("temperature", s.temperature);
            s.maxTokens = o.optInt("maxTokens", s.maxTokens);
            s.stream = o.optBoolean("stream", s.stream);
            s.autoFailover = o.optBoolean("autoFailover", s.autoFailover);
            s.contextLimit = o.optInt("contextLimit", s.contextLimit);
            s.wcChannel = o.optInt("wcChannel", s.wcChannel);
            s.wcToken = o.optString("wcToken", s.wcToken);
            s.wcUrl = o.optString("wcUrl", s.wcUrl);
            s.wcAuto = o.optBoolean("wcAuto", s.wcAuto);
            s.wcVerified = o.optLong("wcVerified", s.wcVerified);
        } catch (JSONException ignored) {
        }

        // 老版本装过的：把没动过的旧默认值抬到更宽松的新默认值
        if (!sp.getBoolean("settingsV2", false)) {
            if (s.maxTokens == 2048 || s.maxTokens == 4096) s.maxTokens = 32768;
            if (Math.abs(s.temperature - 0.7) < 0.001) s.temperature = 1.1;
            if (s.contextLimit == 20) s.contextLimit = 60;
            saveSettings(s);
            sp.edit().putBoolean("settingsV2", true).apply();
        }
        return s;
    }

    public void saveSettings(Settings s) {
        JSONObject o = new JSONObject();
        try {
            o.put("systemPrompt", s.systemPrompt);
            o.put("temperature", s.temperature);
            o.put("maxTokens", s.maxTokens);
            o.put("stream", s.stream);
            o.put("autoFailover", s.autoFailover);
            o.put("contextLimit", s.contextLimit);
            o.put("wcChannel", s.wcChannel);
            o.put("wcToken", s.wcToken);
            o.put("wcUrl", s.wcUrl);
            o.put("wcAuto", s.wcAuto);
            o.put("wcVerified", s.wcVerified);
        } catch (JSONException ignored) {
        }
        sp.edit().putString("settings", o.toString()).apply();
    }

    public static class Settings {
        public String systemPrompt = "你是 OpenClaw，一个运行在安卓手机上的私人 AI 助理。回答要简洁、准确、直接，默认使用中文。";
        public double temperature = 1.1;
        public int maxTokens = 32768;
        public boolean stream = true;
        public boolean autoFailover = true;
        public int contextLimit = 60;

        public int wcChannel = WeChat.CH_NONE;  // 微信推送通道
        public String wcToken = "";             // 企业微信 key / Server酱 SendKey
        public String wcUrl = "";               // 自定义 webhook 地址
        public boolean wcAuto = false;          // 每条回复自动转发
        public long wcVerified = 0;             // 上次验证成功（真发了消息）的时间
    }

    // ------------------------------------------------------------------ 聊天记录

    public List<ChatMessage> history() {
        List<ChatMessage> list = new ArrayList<>();
        String raw = sp.getString("history", null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) list.add(ChatMessage.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    public void saveHistory(List<ChatMessage> list) {
        int from = Math.max(0, list.size() - 300);
        JSONArray arr = new JSONArray();
        for (int i = from; i < list.size(); i++) arr.put(list.get(i).toJson());
        sp.edit().putString("history", arr.toString()).apply();
    }

    public void clearHistory() {
        sp.edit().remove("history").apply();
    }

    // ------------------------------------------------------------------ 用量

    public UsageStats stats() {
        String raw = sp.getString("stats", null);
        if (raw == null) return new UsageStats();
        try {
            return UsageStats.fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return new UsageStats();
        }
    }

    public void saveStats(UsageStats stats) {
        sp.edit().putString("stats", stats.toJson().toString()).apply();
    }

    /** 加锁累加，避免聊天线程和微信服务线程同时读改写把数字吃掉。 */
    private static final Object STATS_LOCK = new Object();

    public void addUsage(String model, Usage u, boolean ok) {
        synchronized (STATS_LOCK) {
            UsageStats st = stats();
            st.add(model == null || model.isEmpty() ? "未知模型" : model, u, ok);
            saveStats(st);
        }
    }

    public void clearStats() {
        sp.edit().remove("stats").apply();
    }

    // ------------------------------------------------------------------ 微信 ClawBot 连接凭据

    /** 扫码确认后微信 iLink 返回的 bot token，null 表示未连接。 */
    public String clawBotToken() {
        return sp.getString("clawBotToken", null);
    }

    public String clawBotId() {
        return sp.getString("clawBotId", null);
    }

    public void saveClawBot(String token, String botId, String userId) {
        if (token == null) {
            sp.edit().remove("clawBotToken").remove("clawBotId").remove("clawBotUserId")
                    .remove("clawBotBaseUrl").apply();
            stopServiceFlag = true;
        } else {
            sp.edit()
                    .putString("clawBotToken", token)
                    .putString("clawBotId", botId)
                    .putString("clawBotUserId", userId)
                    .putString("clawBotBaseUrl", clawBotPendingBaseUrl == null ? "" : clawBotPendingBaseUrl)
                    .putLong("clawBotTime", System.currentTimeMillis())
                    .apply();
            stopServiceFlag = false;
        }
    }

    private volatile boolean stopServiceFlag;
    private volatile String clawBotPendingBaseUrl;

    public boolean shouldStopService() {
        boolean v = stopServiceFlag;
        stopServiceFlag = false;
        return v;
    }

    /** 扫码确认后服务端返回的消息通道地址（confirmed.baseurl），空则用默认。 */
    public String clawBotBaseUrl() {
        String s = sp.getString("clawBotBaseUrl", "");
        return s == null ? "" : s;
    }

    /** 连接成功时先记住 baseUrl，再存凭据。 */
    public void setClawBotPendingBaseUrl(String url) {
        clawBotPendingBaseUrl = url;
    }

    public long clawBotTime() {
        return sp.getLong("clawBotTime", 0);
    }

    /** 断点续传游标：进程被杀后从这里继续拉，避免漏消息。 */
    public String updatesBuf() {
        String s = sp.getString("updatesBuf", "");
        return s == null ? "" : s;
    }

    public void saveUpdatesBuf(String buf) {
        if (buf == null) return;
        sp.edit().putString("updatesBuf", buf).apply();
    }

    // ------------------------------------------------------------------ 运行日志（自诊断）

    private static final int LOG_MAX = 12;

    /** 记一条运行日志，供「微信」页排查用。 */
    public void log(String line) {
        try {
            String old = sp.getString("runLog", "");
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            String entry = ts + " " + line;
            StringBuilder sb = new StringBuilder(entry);
            if (old != null && !old.isEmpty()) {
                String[] lines = old.split("\n");
                int keep = Math.min(lines.length, LOG_MAX - 1);
                for (int i = lines.length - keep; i < lines.length; i++) {
                    sb.append('\n').append(lines[i]);
                }
            }
            sp.edit().putString("runLog", sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public String logText() {
        return sp.getString("runLog", "");
    }

    public void clearLog() {
        sp.edit().remove("runLog").apply();
    }

    // ------------------------------------------------------------------ 预设

    public static final class Defaults {

        /** 名称、协议、Base URL、模型 ID */
        public static final String[][] PRESETS = {
                {"自定义", ModelConfig.P_OPENAI, "", ""},
                {"DeepSeek 深度求索", ModelConfig.P_OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"},
                {"DeepSeek V4.1 Flash", ModelConfig.P_OPENAI, "https://api.deepseek.com/v1", "deepseek-flash"},
                {"智谱 GLM", ModelConfig.P_OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"},
                {"月之暗面 Kimi", ModelConfig.P_OPENAI, "https://api.moonshot.cn/v1", "kimi-latest"},
                {"火山引擎 DeepSeek V4.1", ModelConfig.P_OPENAI, "https://ark.cn-beijing.volces.com/api/v3", "deepseek-v4-1-flash-261017"},
                {"火山引擎 豆包", ModelConfig.P_OPENAI, "https://ark.cn-beijing.volces.com/api/v3", "doubao-1-5-pro-32k-250115"},
                {"阿里通义千问", ModelConfig.P_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"},
                {"硅基流动", ModelConfig.P_OPENAI, "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"},
                {"OpenAI", ModelConfig.P_OPENAI, "https://api.openai.com/v1", "gpt-4o-mini"},
                {"Google Gemini", ModelConfig.P_GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"},
                {"Anthropic Claude", ModelConfig.P_ANTHROPIC, "https://api.anthropic.com/v1", "claude-3-5-sonnet-20241022"},
        };

        public static List<ModelConfig> models() {
            List<ModelConfig> list = new ArrayList<>();

            // 火山引擎（方舟）· DeepSeek V4.1 Flash
            ModelConfig ark = new ModelConfig("火山 · DeepSeek V4.1 Flash",
                    ModelConfig.P_OPENAI,
                    "https://ark.cn-beijing.volces.com/api/v3",
                    "deepseek-v4-1-flash-261017");
            ark.apiKey = ""; // 在「模型」页填入你自己的 Key
            ark.enabled = true;

            // DeepSeek 官方 · V4.1 Flash
            ModelConfig ds = new ModelConfig("DeepSeek 官方 · V4.1 Flash",
                    ModelConfig.P_OPENAI,
                    "https://api.deepseek.com/v1",
                    "deepseek-flash");
            ds.apiKey = ""; // 在「模型」页填入你自己的 Key
            ds.enabled = true;

            list.add(ark);
            list.add(ds);
            return list;
        }
    }
}
