package com.openclaw.android.core;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 配置同步码：OC1: + Base64(gzip(json))，用来跨设备搬配置。 */
public class Sync {

    public static final String PREFIX = "OC1:";

    public static String encode(List<ModelConfig> models, Store.Settings s) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            JSONArray arr = new JSONArray();
            for (ModelConfig m : models) arr.put(m.toJson());
            o.put("m", arr);
            JSONObject w = new JSONObject();
            w.put("c", s.wcChannel);
            w.put("t", s.wcToken);
            w.put("u", s.wcUrl);
            w.put("a", s.wcAuto);
            o.put("w", w);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(o.toString().getBytes(StandardCharsets.UTF_8));
            gz.close();
            return PREFIX + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回 null 表示成功，否则是错误说明。 */
    public static String decode(String text, Store store) {
        if (text == null) return "内容为空";
        String t = text.trim().replace("\n", "").replace("\r", "");
        if (!t.startsWith(PREFIX)) return "不是 OpenClaw 配置码";
        try {
            byte[] gz = Base64.decode(t.substring(PREFIX.length()), Base64.DEFAULT);
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();

            JSONObject o = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            List<ModelConfig> models = new ArrayList<>();
            JSONArray arr = o.optJSONArray("m");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    models.add(ModelConfig.fromJson(arr.getJSONObject(i)));
                }
            }
            if (!models.isEmpty()) store.saveModels(models);

            JSONObject w = o.optJSONObject("w");
            if (w != null) {
                Store.Settings s = store.settings();
                s.wcChannel = w.optInt("c", s.wcChannel);
                s.wcToken = w.optString("t", s.wcToken);
                s.wcUrl = w.optString("u", s.wcUrl);
                s.wcAuto = w.optBoolean("a", s.wcAuto);
                store.saveSettings(s);
            }
            return null;
        } catch (Exception e) {
            return "配置码解析失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
