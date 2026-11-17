package com.openclaw.android.core;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 微信推送通道：只用官方开放接口，不读取、不操控微信客户端。 */
public class WeChat {

    public static final int CH_NONE = 0;
    public static final int CH_QYWX = 1;        // 企业微信群机器人
    public static final int CH_SERVERCHAN = 2;  // Server酱（推送到个人微信）
    public static final int CH_CUSTOM = 3;      // 自定义 Webhook

    public static String channelName(int c) {
        switch (c) {
            case CH_QYWX:
                return "企业微信群机器人";
            case CH_SERVERCHAN:
                return "Server酱";
            case CH_CUSTOM:
                return "自定义 Webhook";
            default:
                return "未启用";
        }
    }

    public static String hint(int c) {
        switch (c) {
            case CH_QYWX:
                return "在企业微信群 → 添加群机器人，复制 Webhook 里的 key 粘贴到这里。";
            case CH_SERVERCHAN:
                return "在 sct.ftqq.com 登录微信扫码后获取 SendKey（形如 SCTxxxxxx）。";
            case CH_CUSTOM:
                return "任意接受 JSON 的地址，会收到 {\"text\":\"...\",\"content\":\"...\"}。";
            default:
                return "选择通道后，可以把 AI 回复一键推到微信。";
        }
    }

    /** 返回 null 表示成功，否则返回错误说明。 */
    public static String push(Store.Settings s, String text) {
        if (s == null) return "设置未加载";
        if (s.wcChannel == CH_NONE) return "尚未启用微信通道";
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) return "内容为空";
        if (content.length() > 3600) content = content.substring(0, 3600) + "…";

        try {
            if (s.wcChannel == CH_QYWX) {
                String key = s.wcToken == null ? "" : s.wcToken.trim();
                if (key.isEmpty()) return "请填写企业微信机器人 key";
                if (key.contains("key=")) {
                    int i = key.indexOf("key=");
                    key = key.substring(i + 4);
                    int amp = key.indexOf('&');
                    if (amp > 0) key = key.substring(0, amp);
                }
                String url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key="
                        + URLEncoder.encode(key, "UTF-8");
                JSONObject body = new JSONObject();
                body.put("msgtype", "text");
                body.put("text", new JSONObject().put("content", content));
                String resp = post(url, body.toString(), "application/json; charset=utf-8");
                JSONObject o = new JSONObject(resp);
                int code = o.optInt("errcode", 0);
                if (code != 0) return "企业微信返回 " + code + "：" + o.optString("errmsg", "未知错误");
                return null;
            }

            if (s.wcChannel == CH_SERVERCHAN) {
                String key = s.wcToken == null ? "" : s.wcToken.trim();
                if (key.isEmpty()) return "请填写 Server酱 SendKey";
                if (key.startsWith("https://")) {
                    int i = key.indexOf(".send");
                    key = key.substring(key.lastIndexOf('/', i) + 1, i);
                }
                String url = "https://sctapi.ftqq.com/" + URLEncoder.encode(key, "UTF-8") + ".send";
                String title = content.length() > 32 ? content.substring(0, 32) + "…" : content;
                String form = "title=" + URLEncoder.encode(title, "UTF-8")
                        + "&desp=" + URLEncoder.encode(content, "UTF-8");
                String resp = post(url, form, "application/x-www-form-urlencoded; charset=utf-8");
                JSONObject o = new JSONObject(resp);
                int code = o.optInt("code", 0);
                if (code != 0) return "Server酱返回 " + code + "：" + o.optString("message", "未知错误");
                return null;
            }

            if (s.wcChannel == CH_CUSTOM) {
                String url = s.wcUrl == null ? "" : s.wcUrl.trim();
                if (url.isEmpty()) return "请填写 Webhook 地址";
                JSONObject body = new JSONObject();
                body.put("text", content);
                body.put("content", content);
                body.put("source", "OpenClaw-Android");
                String resp = post(url, body.toString(), "application/json; charset=utf-8");
                return null;
            }
        } catch (Exception e) {
            String m = e.getMessage();
            return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
        }
        return "未知通道";
    }

    private static String post(String url, String body, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        c.setRequestProperty("User-Agent", "OpenClaw-Android/1.0");
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
        if (code >= 400) throw new Exception("HTTP " + code + " " + sb.toString().trim());
        return sb.toString();
    }
}
