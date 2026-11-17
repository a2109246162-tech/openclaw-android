package com.openclaw.android.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用引擎：按优先级依次尝试模型链上的每个模型，支持流式输出。
 * 支持三种协议：OpenAI 兼容 / Anthropic / Google Gemini。
 */
public class ApiClient {

    public interface Listener {
        void onStart(ModelConfig model);

        void onDelta(String chunk);

        /** 推理模型（如 DeepSeek V4.1 Flash）的思考进度，单位：累计字符数。 */
        void onReasoning(int chars);

        void onDone(ModelConfig model, Usage usage, String fullText);

        void onFailover(ModelConfig failed, String reason);

        void onAllFailed(String reason);

        void onCancelled();
    }

    public static class CanceledException extends IOException {
        public CanceledException() {
            super("已停止");
        }
    }

    private volatile boolean canceled = false;
    private volatile HttpURLConnection current = null;

    public void cancel() {
        canceled = true;
        HttpURLConnection c = current;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isCanceled() {
        return canceled;
    }

    // ------------------------------------------------------------------ 主流程

    public void run(List<ModelConfig> chain, List<ChatMessage> history, Store.Settings s, Listener l) {
        if (chain == null || chain.isEmpty()) {
            l.onAllFailed("还没有可用的模型。请先到「模型」页填写至少一个 API Key。");
            return;
        }
        String lastError = null;
        for (int i = 0; i < chain.size(); i++) {
            if (canceled) {
                l.onCancelled();
                return;
            }
            ModelConfig m = chain.get(i);
            try {
                l.onStart(m);
                Usage u = new Usage();
                String full = call(m, history, s, u, l);
                if (canceled) {
                    l.onCancelled();
                    return;
                }
                u.normalize();
                if (u.total <= 0) {
                    Usage est = Usage.estimate(promptText(history, s), full);
                    u.prompt = est.prompt;
                    u.completion = est.completion;
                    u.total = est.total;
                    u.estimated = true;
                }
                l.onDone(m, u, full);
                return;
            } catch (CanceledException ce) {
                l.onCancelled();
                return;
            } catch (Exception e) {
                if (canceled) {
                    l.onCancelled();
                    return;
                }
                lastError = describe(e);
                l.onFailover(m, lastError);
                if (!s.autoFailover || i >= chain.size() - 1) break;
            }
        }
        l.onAllFailed(lastError == null ? "调用失败" : lastError);
    }

    /** 单模型同步调用（ClawBotService 用）：成功返回正文，异常抛出。 */
    public String call(ModelConfig m, List<ChatMessage> history, Store.Settings s, Usage u, Listener l) throws Exception {
        String proto = m.protocol == null ? ModelConfig.P_OPENAI : m.protocol;
        if (ModelConfig.P_ANTHROPIC.equals(proto)) return callAnthropic(m, history, s, u, l);
        if (ModelConfig.P_GEMINI.equals(proto)) return callGemini(m, history, s, u, l);
        return callOpenAi(m, history, s, u, l);
    }

    // ------------------------------------------------------------------ OpenAI 兼容

    private String callOpenAi(ModelConfig m, List<ChatMessage> history, Store.Settings s, Usage u, Listener l) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", m.modelId.trim());
        JSONArray messages = new JSONArray();

        String sys = s.systemPrompt == null ? "" : s.systemPrompt.trim();
        if (!sys.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", sys));
        }
        for (ChatMessage h : trim(history, s.contextLimit)) {
            if (h.error) continue;
            String img = imageData(h);
            String txt = h.content == null ? "" : h.content;
            if (txt.trim().isEmpty() && img == null) continue;
            JSONObject msg = new JSONObject()
                    .put("role", h.role == ChatMessage.ROLE_USER ? "user" : "assistant");
            if (img != null) {
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "text")
                        .put("text", txt.trim().isEmpty() ? "请看这张图片" : txt));
                parts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", "data:" + ImageStore.mime(h.imagePath) + ";base64," + img)));
                msg.put("content", parts);
            } else {
                msg.put("content", txt);
            }
            messages.put(msg);
        }
        body.put("messages", messages);
        body.put("temperature", clampTemp(s.temperature));
        body.put("max_tokens", s.maxTokens);
        body.put("stream", s.stream);
        if (s.stream) {
            body.put("stream_options", new JSONObject().put("include_usage", true));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + m.apiKey.trim());
        String url = openAiUrl(m.baseUrl);

        if (!s.stream) {
            JSONObject o = new JSONObject(post(url, body.toString(), headers));
            checkError(o);
            String text = "";
            JSONArray choices = o.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.getJSONObject(0);
                JSONObject msg = c0.optJSONObject("message");
                if (msg != null) {
                    text = msg.optString("content", "");
                    if (text.trim().isEmpty()) {
                        String r = msg.optString("reasoning_content", "");
                        if (!r.trim().isEmpty()) text = r;
                    }
                }
                if (text.isEmpty()) text = c0.optString("text", "");
            }
            readOpenAiUsage(u, o.optJSONObject("usage"));
            return text;
        }

        try {
            return streamOpenAi(url, body, headers, u, l);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("stream_options")) {
                body.remove("stream_options");
                return streamOpenAi(url, body, headers, u, l);
            }
            throw e;
        }
    }

    private String streamOpenAi(String url, JSONObject body, Map<String, String> headers, Usage u, Listener l) throws Exception {
        StringBuilder sb = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        HttpURLConnection c = open(url, body.toString(), headers);
        int code = c.getResponseCode();
        if (code >= 400) {
            String msg = errorText(c, code);
            c.disconnect();
            throw new IOException(msg);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (canceled) {
                closeQuietly(br);
                c.disconnect();
                throw new CanceledException();
            }
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            JSONObject o;
            try {
                o = new JSONObject(data);
            } catch (JSONException bad) {
                continue;
            }
            checkError(o);
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) readOpenAiUsage(u, usage);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject c0 = choices.getJSONObject(0);
            String piece = null;
            JSONObject delta = c0.optJSONObject("delta");
            if (delta != null) {
                if (!delta.isNull("content")) {
                    piece = delta.optString("content", "");
                }
                if (!delta.isNull("reasoning_content")) {
                    String r = delta.optString("reasoning_content", "");
                    if (!r.isEmpty()) {
                        reasoning.append(r);
                        l.onReasoning(reasoning.length());
                    }
                }
            }
            if ((piece == null || piece.isEmpty()) && !c0.isNull("text")) {
                piece = c0.optString("text", "");
            }
            if (piece != null && piece.length() > 0) {
                sb.append(piece);
                l.onDelta(piece);
            }
        }
        closeQuietly(br);
        c.disconnect();
        if (sb.length() == 0 && reasoning.length() > 0) {
            // 有些推理模型把答案全程放在 reasoning_content 里
            String r = reasoning.toString();
            sb.append(r);
            l.onDelta(r);
        }
        return sb.toString();
    }

    private void readOpenAiUsage(Usage u, JSONObject o) {
        if (o == null) return;
        u.prompt = o.optInt("prompt_tokens", o.optInt("input_tokens", u.prompt));
        u.completion = o.optInt("completion_tokens", o.optInt("output_tokens", u.completion));
        u.total = o.optInt("total_tokens", u.prompt + u.completion);
    }

    // ------------------------------------------------------------------ Anthropic

    private String callAnthropic(ModelConfig m, List<ChatMessage> history, Store.Settings s, Usage u, Listener l) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", m.modelId.trim());
        body.put("max_tokens", s.maxTokens);
        body.put("temperature", Math.min(1.0, Math.max(0.0, s.temperature)));
        String sys = s.systemPrompt == null ? "" : s.systemPrompt.trim();
        if (!sys.isEmpty()) body.put("system", sys);

        JSONArray messages = new JSONArray();
        for (ChatMessage h : trim(history, s.contextLimit)) {
            if (h.error) continue;
            String img = imageData(h);
            String txt = h.content == null ? "" : h.content;
            if (txt.trim().isEmpty() && img == null) continue;
            JSONObject msg = new JSONObject()
                    .put("role", h.role == ChatMessage.ROLE_USER ? "user" : "assistant");
            if (img != null) {
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "image")
                        .put("source", new JSONObject()
                                .put("type", "base64")
                                .put("media_type", ImageStore.mime(h.imagePath))
                                .put("data", img)));
                parts.put(new JSONObject().put("type", "text")
                        .put("text", txt.trim().isEmpty() ? "请看这张图片" : txt));
                msg.put("content", parts);
            } else {
                msg.put("content", txt);
            }
            messages.put(msg);
        }
        body.put("messages", messages);
        body.put("stream", s.stream);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", m.apiKey.trim());
        headers.put("anthropic-version", "2023-06-01");
        String url = anthropicUrl(m.baseUrl);

        if (!s.stream) {
            JSONObject o = new JSONObject(post(url, body.toString(), headers));
            checkError(o);
            String text = "";
            JSONArray content = o.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) {
                        text += part.optString("text", "");
                    }
                }
            }
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) {
                u.prompt = usage.optInt("input_tokens", 0);
                u.completion = usage.optInt("output_tokens", 0);
                u.total = u.prompt + u.completion;
            }
            return text;
        }

        StringBuilder sb = new StringBuilder();
        HttpURLConnection c = open(url, body.toString(), headers);
        int code = c.getResponseCode();
        if (code >= 400) {
            String msg = errorText(c, code);
            c.disconnect();
            throw new IOException(msg);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (canceled) {
                closeQuietly(br);
                c.disconnect();
                throw new CanceledException();
            }
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            JSONObject o;
            try {
                o = new JSONObject(data);
            } catch (JSONException bad) {
                continue;
            }
            String type = o.optString("type", "");
            if ("content_block_delta".equals(type)) {
                JSONObject delta = o.optJSONObject("delta");
                if (delta != null) {
                    String piece = delta.optString("text", "");
                    if (piece.length() > 0) {
                        sb.append(piece);
                        l.onDelta(piece);
                    }
                }
            } else if ("message_start".equals(type)) {
                JSONObject msg = o.optJSONObject("message");
                if (msg != null) {
                    JSONObject usage = msg.optJSONObject("usage");
                    if (usage != null) {
                        u.prompt = usage.optInt("input_tokens", u.prompt);
                        u.completion = usage.optInt("output_tokens", u.completion);
                    }
                }
            } else if ("message_delta".equals(type)) {
                JSONObject usage = o.optJSONObject("usage");
                if (usage != null) {
                    int out = usage.optInt("output_tokens", 0);
                    if (out > 0) u.completion = out;
                }
            } else if ("error".equals(type)) {
                JSONObject err = o.optJSONObject("error");
                closeQuietly(br);
                c.disconnect();
                throw new IOException(err != null ? err.optString("message", "调用失败") : "调用失败");
            }
        }
        closeQuietly(br);
        c.disconnect();
        u.total = u.prompt + u.completion;
        return sb.toString();
    }

    // ------------------------------------------------------------------ Gemini

    private String callGemini(ModelConfig m, List<ChatMessage> history, Store.Settings s, Usage u, Listener l) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        for (ChatMessage h : trim(history, s.contextLimit)) {
            if (h.error) continue;
            String img = imageData(h);
            String txt = h.content == null ? "" : h.content;
            if (txt.trim().isEmpty() && img == null) continue;
            JSONArray parts = new JSONArray();
            if (img != null) {
                parts.put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", ImageStore.mime(h.imagePath))
                        .put("data", img)));
            }
            parts.put(new JSONObject().put("text", txt.trim().isEmpty() ? "请看这张图片" : txt));
            contents.put(new JSONObject()
                    .put("role", h.role == ChatMessage.ROLE_USER ? "user" : "model")
                    .put("parts", parts));
        }
        body.put("contents", contents);

        String sys = s.systemPrompt == null ? "" : s.systemPrompt.trim();
        if (!sys.isEmpty()) {
            body.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", sys))));
        }
        body.put("generationConfig", new JSONObject()
                .put("temperature", clampTemp(s.temperature))
                .put("maxOutputTokens", s.maxTokens));

        Map<String, String> headers = new LinkedHashMap<>();
        String url = geminiUrl(m.baseUrl, m.modelId.trim(), m.apiKey.trim(), s.stream);

        if (!s.stream) {
            JSONObject o = new JSONObject(post(url, body.toString(), headers));
            checkError(o);
            String text = geminiText(o);
            readGeminiUsage(u, o.optJSONObject("usageMetadata"));
            return text;
        }

        StringBuilder sb = new StringBuilder();
        HttpURLConnection c = open(url, body.toString(), headers);
        int code = c.getResponseCode();
        if (code >= 400) {
            String msg = errorText(c, code);
            c.disconnect();
            throw new IOException(msg);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (canceled) {
                closeQuietly(br);
                c.disconnect();
                throw new CanceledException();
            }
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            JSONObject o;
            try {
                o = new JSONObject(data);
            } catch (JSONException bad) {
                continue;
            }
            checkError(o);
            String piece = geminiText(o);
            if (piece.length() > 0) {
                sb.append(piece);
                l.onDelta(piece);
            }
            readGeminiUsage(u, o.optJSONObject("usageMetadata"));
        }
        closeQuietly(br);
        c.disconnect();
        return sb.toString();
    }

    private String geminiText(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        JSONArray cand = o.optJSONArray("candidates");
        if (cand == null) return "";
        for (int i = 0; i < cand.length(); i++) {
            JSONObject c0 = cand.optJSONObject(i);
            if (c0 == null) continue;
            JSONObject content = c0.optJSONObject("content");
            if (content == null) continue;
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject p = parts.optJSONObject(j);
                if (p != null) sb.append(p.optString("text", ""));
            }
        }
        return sb.toString();
    }

    private void readGeminiUsage(Usage u, JSONObject o) {
        if (o == null) return;
        u.prompt = o.optInt("promptTokenCount", u.prompt);
        u.completion = o.optInt("candidatesTokenCount", u.completion);
        int t = o.optInt("totalTokenCount", 0);
        u.total = t > 0 ? t : u.prompt + u.completion;
    }

    // ------------------------------------------------------------------ HTTP

    private HttpURLConnection open(String url, String body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoInput(true);
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "text/event-stream, application/json");
        c.setRequestProperty("User-Agent", "OpenClaw-Android/1.0");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        current = c;
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        return c;
    }

    /** 非流式请求：一次性读完响应。 */
    private String post(String url, String body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, body, headers);
        int code = c.getResponseCode();
        if (code >= 400) {
            String msg = errorText(c, code);
            c.disconnect();
            throw new IOException(msg);
        }
        InputStream is = c.getInputStream();
        String text = readAll(is);
        is.close();
        c.disconnect();
        return text;
    }

    private String errorText(HttpURLConnection c, int code) {
        String raw = "";
        try {
            InputStream es = c.getErrorStream();
            if (es != null) raw = readAll(es);
        } catch (Exception ignored) {
        }
        String msg = raw.trim();
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (!m.isEmpty()) msg = m;
            } else if (o.has("error_description")) {
                msg = o.optString("error_description");
            } else if (o.has("message")) {
                msg = o.optString("message");
            } else if (o.has("msg")) {
                msg = o.optString("msg");
            }
        } catch (Exception ignored) {
        }
        if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
        String hint = "";
        if (code == 401 || code == 403) hint = "（API Key 无效或没有权限）";
        else if (code == 404) hint = "（接口地址或模型名不对）";
        else if (code == 429) hint = "（触发限流，稍后再试或换下一个模型）";
        return "HTTP " + code + hint + " " + msg;
    }

    private String readAll(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private void closeQuietly(BufferedReader br) {
        try {
            br.close();
        } catch (Exception ignored) {
        }
    }

    private void checkError(JSONObject o) throws IOException {
        if (o == null) return;
        Object err = o.opt("error");
        if (err == null || err == JSONObject.NULL) return;
        String msg;
        if (err instanceof JSONObject) {
            msg = ((JSONObject) err).optString("message", "调用失败");
        } else {
            msg = String.valueOf(err);
        }
        if (msg.trim().isEmpty()) msg = "调用失败";
        throw new IOException(msg);
    }

    // ------------------------------------------------------------------ 小工具

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = e.getClass().getSimpleName();
            if (e instanceof java.net.UnknownHostException) msg = "无法连接服务器，检查网络或接口地址";
            else if (e instanceof java.net.SocketTimeoutException) msg = "请求超时";
            else if (e instanceof javax.net.ssl.SSLException) msg = "HTTPS 握手失败：" + msg;
            else if (e instanceof java.net.ConnectException) msg = "连接被拒绝";
        }
        return msg;
    }

    private static double clampTemp(double v) {
        if (v < 0) return 0;
        if (v > 2) return 2;
        return v;
    }

    private static List<ChatMessage> trim(List<ChatMessage> history, int limit) {
        if (history == null) return new java.util.ArrayList<>();
        int n = limit <= 0 ? history.size() : Math.min(limit, history.size());
        return history.subList(history.size() - n, history.size());
    }

    private static String promptText(List<ChatMessage> history, Store.Settings s) {
        StringBuilder sb = new StringBuilder();
        if (s.systemPrompt != null) sb.append(s.systemPrompt);
        for (ChatMessage h : history) sb.append(h.content);
        return sb.toString();
    }

    /** 有图就转 base64，没有或读不到返回 null。 */
    private static String imageData(ChatMessage h) {
        if (h == null || h.imagePath == null) return null;
        if (!new java.io.File(h.imagePath).exists()) return null;
        return ImageStore.base64(h.imagePath);
    }

    private static String trimSlash(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String openAiUrl(String base) {
        String b = trimSlash(base);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    static String anthropicUrl(String base) {
        String b = trimSlash(base);
        if (b.endsWith("/messages")) return b;
        return b + "/messages";
    }

    static String geminiUrl(String base, String model, String key, boolean stream) throws IOException {
        String b = trimSlash(base);
        String action = stream ? ":streamGenerateContent?alt=sse&key=" : ":generateContent?key=";
        String k = java.net.URLEncoder.encode(key, "UTF-8");
        if (b.endsWith("/models")) return b + "/" + model + action + k;
        return b + "/models/" + model + action + k;
    }
}
