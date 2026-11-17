package com.openclaw.android.core;

/** 单次调用的 token 用量。 */
public class Usage {
    public int prompt;
    public int completion;
    public int total;
    public boolean estimated;

    public void normalize() {
        if (total <= 0) total = prompt + completion;
    }

    public Usage copy() {
        Usage u = new Usage();
        u.prompt = prompt;
        u.completion = completion;
        u.total = total;
        u.estimated = estimated;
        return u;
    }

    /** 服务端没给 usage 时的兜底估算：中日韩字符≈1 token，其它按 4 字符≈1 token。 */
    public static Usage estimate(String promptText, String completionText) {
        Usage u = new Usage();
        u.prompt = rough(promptText);
        u.completion = rough(completionText);
        u.total = u.prompt + u.completion;
        u.estimated = true;
        return u;
    }

    private static int rough(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) cjk++;
            else other++;
        }
        double t = cjk * 0.75 + other / 4.0;
        int n = (int) Math.round(t);
        return Math.max(n, 1);
    }
}
