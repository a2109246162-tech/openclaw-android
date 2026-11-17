package com.openclaw.android.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 图片：压缩存本地 + 转 base64 发模型。不引第三方库。 */
public class ImageStore {

    /** 从相册 URI 读图，压到 1280px 内、JPEG 82，存进私有目录，返回路径。 */
    public static String save(Context c, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream is = c.getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("open failed");
        BitmapFactory.decodeStream(is, null, o);
        is.close();

        int sample = 1;
        while (o.outWidth / sample > 1280 || o.outHeight / sample > 1280) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, sample);

        is = c.getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(is, null, o);
        if (is != null) is.close();
        if (bmp == null) throw new Exception("decode failed");

        File dir = new File(c.getFilesDir(), "img");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, System.currentTimeMillis() + ".jpg");
        FileOutputStream fos = new FileOutputStream(f);
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos);
        fos.flush();
        fos.close();
        bmp.recycle();
        return f.getAbsolutePath();
    }

    /** 保存字节流（微信图片解密后）。返回路径。 */
    public static String saveBytes(Context c, byte[] data) throws Exception {
        File dir = new File(c.getFilesDir(), "img");
        if (!dir.exists()) dir.mkdirs();
        String ext = ".jpg";
        if (data.length > 8) {
            if ((data[0] & 0xFF) == 0x89 && data[1] == 'P') ext = ".png";      // PNG
            else if ((data[0] & 0xFF) == 0x47 && data[1] == 'I') ext = ".gif"; // GIF
        }
        File f = new File(dir, "wx_" + System.currentTimeMillis() + ext);
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.flush();
        fos.close();
        return f.getAbsolutePath();
    }

    /** 缩略图，用于气泡里显示。 */
    public static Bitmap thumb(String path, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample > maxPx || o.outHeight / sample > maxPx) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读文件转 base64（不带 data: 前缀）。 */
    public static String base64(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            byte[] raw = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static String mime(String path) {
        return "image/jpeg";
    }

    public static void delete(String path) {
        try {
            if (path != null) new File(path).delete();
        } catch (Exception ignored) {
        }
    }
}
