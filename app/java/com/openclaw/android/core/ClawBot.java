package com.openclaw.android.core;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 微信 ClawBot 官方接入协议（腾讯 iLink 开放接口）。
 * 与 @tencent-weixin/openclaw-weixin 插件走同一组端点：
 *   POST ilink/bot/get_bot_qrcode?bot_type=3   -> 拿二维码
 *   GET  ilink/bot/get_qrcode_status?qrcode=…  -> 长轮询扫码状态
 *   POST ilink/bot/getupdates                  -> 长轮询收消息（需 Bearer bot_token）
 *   POST ilink/bot/sendmessage                 -> 回复消息（需 Bearer bot_token）
 * 状态流转：wait -> scaned -> (need_verifycode) -> confirmed{bot_token,...}
 */
public class ClawBot {

    static final String BASE = "https://ilinkai.weixin.qq.com";
    static final String APP_ID = "bot";
    static final int CLIENT_VERSION = 132106; // 对应插件 2.4.10
    static final String CHANNEL_VERSION = "2.4.10";

    // 消息枚举（与官方 proto 对齐）
    static final int MSG_TYPE_BOT = 2;
    static final int ITEM_TYPE_TEXT = 1;
    static final int ITEM_TYPE_IMAGE = 2;
    static final int STATE_FINISH = 2;
    static final String CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";

    /** 一次登录会话。 */
    public static class Session {
        public String qrcode;      // 凭据 id
        public String qrUrl;       // 二维码内容（https://liteapp.weixin.qq.com/q/...）
        public String status = "wait";
        public String botToken;
        public String botId;
        public String userId;
        public String baseUrl;
        public String error;
        public String rawConfirmed;   // 脱敏后的确认报文，排查用

        /** 兼容不同字段名。 */
        static String firstNonEmpty(JSONObject o, String... keys) {
            for (String k : keys) {
                String v = o.optString(k, "");
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
            return null;
        }

        /** token 只留首尾，便于排查又不泄露。 */
        static String mask(String s) {
            if (s == null) return "";
            String out = s.replaceAll("(\"(?:bot_token|token)\"\\s*:\\s*\")([^\"]{6})[^\"]*", "$1$2***");
            return out.length() > 400 ? out.substring(0, 400) + "…" : out;
        }
    }

    public interface StatusListener {
        /** 回调在后台线程。返回 wait/scaned/need_verifycode/confirmed/expired/failed。 */
        void onStatus(Session s);
    }

    /** 请求一个新二维码。失败返回 null 并填 error。 */
    public static Session createQr() {
        return createQr(null);
    }

    /**
     * 请求二维码。localToken 用于告诉微信"本机已经有过哪个 bot"，
     * 与官方插件一致：避免重复建号，重复扫码会复用/重定向到已有 bot。
     */
    public static Session createQr(String localToken) {
        try {
            String tokens = "[]";
            if (localToken != null && !localToken.trim().isEmpty()) {
                tokens = "[" + JSONObject.quote(localToken.trim()) + "]";
            }
            String body = "{\"local_token_list\":" + tokens
                    + ",\"base_info\":{\"channel_version\":\"" + CHANNEL_VERSION
                    + "\",\"bot_agent\":\"OpenClaw-Android\"}}";
            String resp = request("POST",
                    BASE + "/ilink/bot/get_bot_qrcode?bot_type=3", body);
            JSONObject o = new JSONObject(resp);
            if (o.optInt("ret", -1) != 0 || o.optString("qrcode", "").isEmpty()) {
                Session s = new Session();
                s.error = "微信接口返回异常：" + resp;
                return s;
            }
            Session s = new Session();
            s.qrcode = o.getString("qrcode");
            s.qrUrl = o.getString("qrcode_img_content");
            return s;
        } catch (Exception e) {
            Session s = new Session();
            s.error = describe(e);
            return s;
        }
    }

    /** 轮询一次状态（接口本身就是长轮询，约 30s 返回）。 */
    public static Session poll(Session s) {
        try {
            String resp = request("GET",
                    BASE + "/ilink/bot/get_qrcode_status?qrcode=" + url(s.qrcode), null);
            JSONObject o = new JSONObject(resp);
            s.status = o.optString("status", "wait");
            if ("confirmed".equals(s.status)) {
                s.botToken = Session.firstNonEmpty(o, "bot_token", "token", "ilink_bot_token");
                s.botId = Session.firstNonEmpty(o, "ilink_bot_id", "bot_id");
                s.userId = o.optString("ilink_user_id", null);
                s.baseUrl = o.optString("baseurl", null);
                s.rawConfirmed = Session.mask(o.toString());
                if (s.botToken == null || s.botId == null) {
                    s.status = "failed";
                    s.error = "服务器未返回完整凭据：" + s.rawConfirmed;
                }
            }
            return s;
        } catch (Exception e) {
            s.status = "wait"; // 网络抖动按插件同样的策略当作继续等待
            return s;
        }
    }

    // ------------------------------------------------------------------ 自检

    public static class Probe {
        public boolean httpOk;
        public int errcode;
        public int msgCount;
        public String raw = "";
        public String error;
        public long costMs;
    }

    /**
     * 前台自检：带 token 打一次 getupdates（短超时），把原始返回原样带回。
     * 用来判断到底是"收不到消息"还是"服务没在跑"。
     */
    public static Probe probe(String token, String baseUrl, int timeoutMs) {
        Probe p = new Probe();
        long t0 = System.currentTimeMillis();
        try {
            String base = (baseUrl == null || baseUrl.trim().isEmpty()) ? BASE : baseUrl.trim();
            if (!base.endsWith("/")) base += "/";
            String body = "{\"get_updates_buf\":\"\",\"base_info\":{\"channel_version\":\""
                    + CHANNEL_VERSION + "\",\"bot_agent\":\"OpenClaw-Android\"}}";
            HttpURLConnection c = (HttpURLConnection) new URL(base + "ilink/bot/getupdates").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(timeoutMs);
            c.setDoInput(true);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("iLink-App-Id", APP_ID);
            c.setRequestProperty("iLink-App-ClientVersion", String.valueOf(CLIENT_VERSION));
            c.setRequestProperty("AuthorizationType", "ilink_bot_token");
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
            c.setRequestProperty("X-WECHAT-UIN", randomUin());
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            c.disconnect();
            p.httpOk = code < 400;
            p.raw = sb.toString();
            if (p.raw.length() > 900) p.raw = p.raw.substring(0, 900) + "…";
            JSONObject o = new JSONObject(p.raw);
            p.errcode = o.optInt("errcode", 0);
            org.json.JSONArray msgs = o.optJSONArray("msgs");
            p.msgCount = msgs == null ? 0 : msgs.length();
            if (code >= 400) p.error = "HTTP " + code;
        } catch (java.net.SocketTimeoutException te) {
            p.error = "超时（服务端没在 10 秒内返回，属正常长轮询行为）";
        } catch (Exception e) {
            p.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        p.costMs = System.currentTimeMillis() - t0;
        return p;
    }

    // ------------------------------------------------------------------ 收消息

    /** 一条入站微信消息。 */
    public static class Inbound {
        public String fromUserId;
        public String text;
        public String contextToken;
        public String messageId;
        public boolean hasImage;
        public String imageParam;    // CDN encrypt_query_param
        public String imageFullUrl;  // CDN full_url
        public String imageAesKey;   // base64(16B) 或 hex(32字符)
    }

    /** getUpdates 长轮询结果。 */
    public static class Updates {
        public java.util.List<Inbound> msgs = new java.util.ArrayList<>();
        public String buf = "";
        public int errcode;
        public String errmsg;
        public boolean ok;
    }

    /**
     * 长轮询收消息。服务端会挂住最多 ~35s；返回时带新消息和续传 buf。
     * token 失效时 ok=false 且 errcode=-14。
     */
    public static Updates getUpdates(String token, String baseUrl, String buf) {
        Updates u = new Updates();
        try {
            String base = (baseUrl == null || baseUrl.trim().isEmpty()) ? BASE : baseUrl.trim();
            if (!base.endsWith("/")) base += "/";
            String body = "{\"get_updates_buf\":" + quote(buf == null ? "" : buf)
                    + ",\"base_info\":{\"channel_version\":\"" + CHANNEL_VERSION
                    + "\",\"bot_agent\":\"OpenClaw-Android\"}}";
            String resp = requestAuthed("POST", base + "ilink/bot/getupdates", body, token, 45000);
            JSONObject o = new JSONObject(resp);
            u.errcode = o.optInt("errcode", 0);
            u.errmsg = o.optString("errmsg", "");
            if (u.errcode != 0) return u;
            u.ok = true;
            u.buf = o.optString("get_updates_buf", buf == null ? "" : buf);
            org.json.JSONArray arr = o.optJSONArray("msgs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    Inbound in = new Inbound();
                    in.fromUserId = m.optString("from_user_id", "");
                    in.contextToken = m.optString("context_token", null);
                    in.messageId = m.optString("message_id", null);
                    org.json.JSONArray items = m.optJSONArray("item_list");
                    if (items != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.optJSONObject(j);
                            if (item == null) continue;
                            if (item.optInt("type", 0) == ITEM_TYPE_TEXT) {
                                JSONObject ti = item.optJSONObject("text_item");
                                if (ti != null) sb.append(ti.optString("text", ""));
                            } else if (item.optInt("type", 0) == ITEM_TYPE_IMAGE) {
                                JSONObject img = item.optJSONObject("image_item");
                                if (img != null) {
                                    in.hasImage = true;
                                    JSONObject media = img.optJSONObject("media");
                                    if (media != null) {
                                        in.imageParam = media.optString("encrypt_query_param", null);
                                        in.imageFullUrl = media.optString("full_url", null);
                                        in.imageAesKey = media.optString("aes_key", null);
                                    }
                                    String hexKey = img.optString("aeskey", null);
                                    if (hexKey != null && hexKey.length() == 32) {
                                        in.imageAesKey = Base64.getEncoder().encodeToString(hexToBytes(hexKey));
                                    }
                                }
                            }
                        }
                        in.text = sb.toString();
                    }
                    if (in.fromUserId != null && !in.fromUserId.isEmpty()) {
                        u.msgs.add(in);
                    }
                }
            }
        } catch (java.net.SocketTimeoutException te) {
            // 服务端长轮询到点返回：属于正常，继续下一轮
            u.ok = true;
            u.buf = buf == null ? "" : buf;
        } catch (Exception e) {
            // 网络抖动按空结果处理，循环继续
            u.errmsg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return u;
    }

    // ------------------------------------------------------------------ 图片下载解密

    /** 下载微信图片并 AES-128-ECB 解密，失败抛异常。 */
    public static byte[] fetchImage(Inbound in) throws Exception {
        String url;
        if (in.imageFullUrl != null && !in.imageFullUrl.isEmpty()) {
            url = in.imageFullUrl;
        } else if (in.imageParam != null && !in.imageParam.isEmpty()) {
            url = CDN_BASE + "/download?encrypted_query_param="
                    + java.net.URLEncoder.encode(in.imageParam, "UTF-8");
        } else {
            throw new Exception("没有图片地址");
        }
        byte[] raw = httpGetBytes(url);
        if (in.imageAesKey == null || in.imageAesKey.isEmpty()) return raw;
        byte[] key = parseAesKey(in.imageAesKey);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"));
        return cipher.doFinal(raw);
    }

    /** base64 解出来可能是 16 字节裸 key，也可能是 32 字符 hex。 */
    private static byte[] parseAesKey(String key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(key);
        if (decoded.length == 16) return decoded;
        if (decoded.length == 32) return hexToBytes(new String(decoded, StandardCharsets.US_ASCII));
        throw new Exception("aes_key 长度异常: " + decoded.length);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "OpenClaw-Android/1.0");
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new Exception("CDN HTTP " + code);
        }
        java.io.InputStream is = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        c.disconnect();
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 发消息

    /** 回复文本。成功返回 null，失败返回错误说明。 */
    public static String sendMessage(String token, String baseUrl, String toUserId,
                                     String text, String contextToken) {
        try {
            String base = (baseUrl == null || baseUrl.trim().isEmpty()) ? BASE : baseUrl.trim();
            if (!base.endsWith("/")) base += "/";
            StringBuilder items = new StringBuilder();
            // 微信通道单条有长度限制，超长自动切段
            java.util.List<String> parts = splitForWeChat(text);
            for (int i = 0; i < parts.size(); i++) {
                String clientId = "openclaw-weixin-" + System.currentTimeMillis() + "-" + i;
                StringBuilder sb = new StringBuilder();
                sb.append("{\"msg\":{\"from_user_id\":\"\",\"to_user_id\":").append(quote(toUserId))
                        .append(",\"client_id\":").append(quote(clientId))
                        .append(",\"message_type\":").append(MSG_TYPE_BOT)
                        .append(",\"message_state\":").append(STATE_FINISH)
                        .append(",\"item_list\":[{\"type\":").append(ITEM_TYPE_TEXT)
                        .append(",\"text_item\":{\"text\":").append(quote(parts.get(i))).append("}}]");
                if (contextToken != null && !contextToken.isEmpty()) {
                    sb.append(",\"context_token\":").append(quote(contextToken));
                }
                sb.append("},\"base_info\":{\"channel_version\":\"").append(CHANNEL_VERSION)
                        .append("\",\"bot_agent\":\"OpenClaw-Android\"}}");
                String resp = requestAuthed("POST", base + "ilink/bot/sendmessage", sb.toString(), token, 20000);
                JSONObject o = new JSONObject(resp);
                int ret = o.optInt("ret", 0);
                int errcode = o.optInt("errcode", 0);
                if (ret != 0 || errcode != 0) {
                    return "ret=" + ret + " errcode=" + errcode + " " + o.optString("errmsg", "");
                }
            }
            return null;
        } catch (Exception e) {
            String m = e.getMessage();
            return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
        }
    }

    /** 微信单条消息上限约 2000 字，按段落切块。 */
    private static java.util.List<String> splitForWeChat(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("（空回复）");
            return out;
        }
        int MAX = 1800;
        if (text.length() <= MAX) {
            out.add(text);
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX, text.length());
            if (end < text.length()) {
                int br = text.lastIndexOf('\n', end);
                if (br > start + MAX / 2) end = br + 1;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    private static String quote(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ------------------------------------------------------------------ HTTP

    private static String url(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    private static String request(String method, String url, String body) throws Exception {
        return requestAuthed(method, url, body, null,
                body != null && body.contains("get_bot_qrcode") ? 30000 : 20000);
    }

    /** 带/不带 Bearer 的通用 JSON 请求。 */
    private static String requestAuthed(String method, String url, String body,
                                        String token, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeoutMs);
        c.setDoInput(true);
        c.setRequestProperty("iLink-App-Id", APP_ID);
        c.setRequestProperty("iLink-App-ClientVersion", String.valueOf(CLIENT_VERSION));
        c.setRequestProperty("AuthorizationType", "ilink_bot_token");
        c.setRequestProperty("X-WECHAT-UIN", randomUin());
        c.setRequestProperty("User-Agent", "OpenClaw-Android/1.0");
        if (token != null && !token.trim().isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return sb.toString();
    }

    /** X-WECHAT-UIN: 随机 uint32 的十进制字符串再 base64，与官方插件一致。 */
    private static String randomUin() {
        java.util.Random r = new java.util.Random();
        long v = (long) (Math.abs(r.nextLong()) % 4294967296L);
        return Base64.getEncoder().encodeToString(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) return "连不上微信服务器，请检查网络";
        return m;
    }
}
