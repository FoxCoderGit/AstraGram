package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import android.graphics.*;
import android.widget.TextView;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.GhostProfileMusicCell;
import org.telegram.ui.Components.BackupImageView;
import java.util.concurrent.atomic.AtomicReference;

/** Render a synthetic card; no profiles, contacts, or music APIs are queried. */
public class GhostMusicDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    @Override public void onStart() {
        Bundle report = new Bundle(); AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                GhostProfileMusicCell cell = new GhostProfileMusicCell(getTargetContext(), null);
                TLRPC.TL_document document = new TLRPC.TL_document(); document.id = -8877;
                document.mime_type = "audio/ogg"; // Disable external album-art search for this fixture.
                TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
                audio.title = "GODMODE"; audio.performer = "4WHEEL"; audio.duration = 183;
                document.attributes.add(audio);
                cell.set(UserConfig.selectedAccount, document, null, 0);
                if (!cell.getContentDescription().toString().contains("GODMODE") || !cell.getContentDescription().toString().contains("4WHEEL")) throw new AssertionError("Music metadata missing");
                Bitmap cover = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
                Canvas art = new Canvas(cover); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setShader(new LinearGradient(0, 0, 160, 160, 0xffdc65fc, 0xff290050, Shader.TileMode.CLAMP)); art.drawRect(0, 0, 160, 160, paint);
                paint.setShader(null); paint.setColor(0xffb48afa); paint.setStrokeWidth(7); paint.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 5; i++) art.drawCircle(80, 80, 18 + i * 15, paint);
                ((BackupImageView) field(cell, "cover")).setImageBitmap(cover);
                ((TextView) field(cell, "album")).setText("GODMODE - Single");
                int width = AndroidUtilities.dp(360);
                cell.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), 0);
                cell.layout(0, 0, width, cell.getMeasuredHeight());
                if (cell.getHeight() != AndroidUtilities.dp(112)) throw new AssertionError("Card height");
                Bitmap image = Bitmap.createBitmap(width, cell.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(image); canvas.drawColor(Color.BLACK); cell.draw(canvas);
                if (image.getPixel(0, 0) != Color.BLACK || image.getPixel(width / 2, AndroidUtilities.dp(16)) == Color.BLACK) throw new AssertionError("Card margins/background");
                try (java.io.FileOutputStream stream = getTargetContext().openFileOutput("ghost_music_preview.png", android.content.Context.MODE_PRIVATE)) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                image.recycle();
                audio.title = "A very long song title which must stay inside this card";
                TLRPC.TL_document other = new TLRPC.TL_document(); other.id = -8878; other.mime_type = "audio/ogg"; other.attributes.add(audio);
                cell.set(UserConfig.selectedAccount, other, null, 0);
                width = AndroidUtilities.dp(240);
                cell.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), 0);
                cell.layout(0, 0, width, cell.getMeasuredHeight());
                TextView title = (TextView) field(cell, "title");
                if (title.getLayout() == null || title.getLayout().getEllipsisCount(0) == 0) throw new AssertionError("Narrow card title overflow");
                cell.set(UserConfig.selectedAccount, null, null, 0);
                if (cell.getContentDescription().toString().contains("4WHEEL")) throw new AssertionError("Recycled card metadata leaked");
                report.putString("result", "PASS: native card render, cover palette, title/artist, album layout, margins, narrow title ellipsis, recycled metadata reset");
            } catch (Throwable problem) { error.set(problem); }
        });
        if (error.get() != null) report.putString("result", android.util.Log.getStackTraceString(error.get()));
        finish(error.get() == null ? -1 : 0, report);
    }
}
