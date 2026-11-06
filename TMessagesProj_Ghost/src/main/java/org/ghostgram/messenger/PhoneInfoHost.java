package org.ghostgram.messenger;

import android.content.Context;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Independent of the plugin runtime: no plugins are imported or executed. */
public final class PhoneInfoHost {
    public static synchronized String lookup(Context context, String phone, String ownPhone) throws Exception {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        File database = new File(context.getCacheDir(), "ru-phone-ranges-v37.bin.gz");
        if (!database.isFile()) {
            File temporary = new File(database.getPath() + ".tmp");
            try (InputStream input = context.getAssets().open("phone/ru-ranges.pack");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            if (!temporary.renameTo(database)) throw new java.io.IOException("Cannot prepare phone database");
        }
        return Python.getInstance().getModule("phone_info").callAttr("lookup_json", phone, ownPhone, database.getPath()).toString();
    }
}
