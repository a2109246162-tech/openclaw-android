package com.openclaw.android.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Token 累计统计 + 最近调用日志。 */
public class UsageStats {

    public int requests;
    public int prompt;
    public int completion;
    public int total;
    public boolean anyEstimated;

    /** 模型名 -> [输入, 输出, 次数] */
    public final LinkedHashMap<String, int[]> byModel = new LinkedHashMap<>();
    public final List<LogItem> log = new ArrayList<>();

    public static class LogItem {
        public long time;
        public String model = "";
        public int prompt;
        public int completion;
        public boolean ok = true;
        public String note = "";
    }

    public void add(String model, Usage u, boolean ok) {
        requests++;
        prompt += u.prompt;
        completion += u.completion;
        // 合计永远由输入+输出推导，保证三个数字永远自洽
        total = prompt + completion;
        if (u.estimated) anyEstimated = true;

        int[] row = byModel.get(model);
        if (row == null) {
            row = new int[]{0, 0, 0};
            byModel.put(model, row);
        }
        row[0] += u.prompt;
        row[1] += u.completion;
        row[2] += 1;

        LogItem item = new LogItem();
        item.time = System.currentTimeMillis();
        item.model = model;
        item.prompt = u.prompt;
        item.completion = u.completion;
        item.ok = ok;
        log.add(0, item);
        while (log.size() > 60) log.remove(log.size() - 1);
    }

    public void reset() {
        requests = 0;
        prompt = 0;
        completion = 0;
        total = 0;
        anyEstimated = false;
        byModel.clear();
        log.clear();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("requests", requests);
            o.put("prompt", prompt);
            o.put("completion", completion);
            o.put("total", total);
            o.put("est", anyEstimated);
            JSONObject models = new JSONObject();
            for (Map.Entry<String, int[]> e : byModel.entrySet()) {
                JSONArray v = new JSONArray();
                v.put(e.getValue()[0]);
                v.put(e.getValue()[1]);
                v.put(e.getValue()[2]);
                models.put(e.getKey(), v);
            }
            o.put("byModel", models);
            JSONArray arr = new JSONArray();
            for (LogItem it : log) {
                JSONObject x = new JSONObject();
                x.put("t", it.time);
                x.put("m", it.model);
                x.put("p", it.prompt);
                x.put("c", it.completion);
                x.put("ok", it.ok);
                x.put("n", it.note);
                arr.put(x);
            }
            o.put("log", arr);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static UsageStats fromJson(JSONObject o) {
        UsageStats s = new UsageStats();
        s.requests = o.optInt("requests");
        s.prompt = o.optInt("prompt");
        s.completion = o.optInt("completion");
        s.total = o.optInt("total");
        s.anyEstimated = o.optBoolean("est");
        JSONObject models = o.optJSONObject("byModel");
        if (models != null) {
            java.util.Iterator<String> it = models.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray v = models.optJSONArray(k);
                if (v != null && v.length() >= 3) {
                    s.byModel.put(k, new int[]{v.optInt(0), v.optInt(1), v.optInt(2)});
                }
            }
        }
        JSONArray arr = o.optJSONArray("log");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                LogItem it = new LogItem();
                it.time = x.optLong("t");
                it.model = x.optString("m");
                it.prompt = x.optInt("p");
                it.completion = x.optInt("c");
                it.ok = x.optBoolean("ok", true);
                it.note = x.optString("n");
                s.log.add(it);
            }
        }
        s.total = s.prompt + s.completion;
        return s;
    }
}
