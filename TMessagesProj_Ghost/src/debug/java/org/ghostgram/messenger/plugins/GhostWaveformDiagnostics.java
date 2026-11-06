package org.ghostgram.messenger.plugins;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.ui.Components.SeekBarWaveform;
import org.telegram.ui.Components.WaveformDrawingSheet;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Only creates a local voice fixture and opens the editor; never sends messages. */
public class GhostWaveformDiagnostics extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args = args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            runOnMainSync(() -> ApplicationLoader.postInitApplication());
            int[] levels = new int[100];
            for (int i = 0; i < levels.length; i++) levels[i] = i % 32;
            MessageObject message = GhostMediaTools.drawnVoiceMessage(0, 3, levels);
            if (!message.isVoice()) throw new AssertionError("Not a voice message");
            TLRPC.DocumentAttribute audio = message.getDocument().attributes.get(0);
            if (audio.duration != 3 || !audio.voice || (audio.flags & 4) == 0) throw new AssertionError("Missing waveform/duration/voice flags");
            SeekBarWaveform renderer = new SeekBarWaveform(getTargetContext());
            renderer.setDelegate(progress -> { });
            renderer.setWaveform(audio.waveform);
            Method calculate = SeekBarWaveform.class.getDeclaredMethod("calculateHeights", int.class);
            calculate.setAccessible(true);
            float[] heights = (float[]) calculate.invoke(renderer, 100);
            for (int i = 0; i < levels.length; i++) {
                if (Math.abs(heights[i] - 7 * levels[i] / 31f) > 0.0001f) throw new AssertionError("Waveform changed at bar " + i + ": " + heights[i]);
            }
            for (WaveformPresets.Template template : WaveformPresets.Template.values()) {
                int[] preset = WaveformPresets.create(template);
                renderer.setWaveform(GhostMediaTools.packWaveform(preset));
                float[] decoded = (float[]) calculate.invoke(renderer, 100);
                for (int i = 0; i < preset.length; i++) {
                    if (Math.abs(decoded[i] - 7 * preset[i] / 31f) > 0.0001f) throw new AssertionError(template + " changed at " + i);
                }
            }
            report.putString("presets", "PASS: all " + WaveformPresets.Template.values().length + " compact presets survive packing and the actual Android renderer");
            SerializedData wire = new SerializedData();
            audio.serializeToStream(wire);
            SerializedData read = new SerializedData(wire.toByteArray());
            TLRPC.DocumentAttribute copy = TLRPC.DocumentAttribute.TLdeserialize(read, read.readInt32(true), true);
            if (!copy.voice || copy.duration != 3 || !Arrays.equals(copy.waveform, audio.waveform)) throw new AssertionError("TL serialization lost waveform");
            read.cleanup(); wire.cleanup();
            File fixture = new File(ApplicationLoader.applicationContext.getFilesDir(), "ghost_media_tests/drawing.ogg");
            fixture.getParentFile().mkdirs();
            Files.copy(new File(message.messageOwner.attachPath).toPath(), fixture.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new File(message.messageOwner.attachPath).delete();
            report.putString("waveform", "PASS: all 100 bars match the chat renderer and TL roundtrip");
            report.putString("voice", "PASS: three-second silent Ogg/Opus created");
            if (args != null && args.getString("preview") != null) {
                Activity activity = startActivitySync(new Intent(getTargetContext(), org.telegram.ui.LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                WaveformDrawingSheet[] sheet = new WaveformDrawingSheet[1];
                runOnMainSync(() -> { sheet[0] = new WaveformDrawingSheet(activity, null, (seconds, samples, done) -> done.accept(false)); sheet[0].show(); });
                Thread.sleep(20000);
                runOnMainSync(() -> sheet[0].dismiss());
            }
            finish(-1, report);
        } catch (Throwable error) { report.putString("error", android.util.Log.getStackTraceString(error)); finish(0, report); }
    }
}
