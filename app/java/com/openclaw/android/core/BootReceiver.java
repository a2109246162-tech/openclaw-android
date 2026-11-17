package com.openclaw.android.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机 / 应用更新后自动把回复服务拉起来。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            Store store = new Store(c);
            String token = store.clawBotToken();
            if (token == null || token.isEmpty()) return;
            Intent svc = new Intent(c, ClawBotService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(svc);
            } else {
                c.startService(svc);
            }
            store.log("开机自启：已拉起回复服务");
        } catch (Throwable e) {
            try {
                new Store(c).log("开机自启失败：" + e.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
    }
}
