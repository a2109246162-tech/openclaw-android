package com.openclaw.android.ui;

import com.openclaw.android.core.Store;

/** 页面与宿主 Activity 之间的最小契约。 */
public interface Host {
    Store store();

    void goTab(int index);

    void refreshHeader();

    void toast(String msg);

    /** 用系统分享把文本发到微信（真发，不是模拟）。 */
    void shareToWeChat(String text);
}
