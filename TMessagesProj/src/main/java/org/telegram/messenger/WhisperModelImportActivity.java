package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

/** Imports the multilingual Whisper Large v3 Turbo Q5_0 model without blocking the UI. */
public class WhisperModelImportActivity extends Activity {
    private static final int PICK_MODEL = 4104;
    private static final DispatchQueue importQueue = new DispatchQueue("whisperModelImport");
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        Toast.makeText(this, R.string.GhostTranscriptionSelectModelFolder, Toast.LENGTH_LONG).show();
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(pick, PICK_MODEL);
    }
    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish(); return;
        }
        Uri uri = data.getData();
        importQueue.postRunnable(() -> {
            boolean success = false;
            File active = LocalWhisper.getModelFile();
            File stage = new File(active.getParentFile(), active.getName() + ".part");
            File backup = new File(active.getParentFile(), active.getName() + ".old");
            try {
                if (!active.getParentFile().isDirectory() && !active.getParentFile().mkdirs()) throw new IllegalStateException("Cannot create model directory");
                try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(stage)) {
                    if (input == null) throw new IllegalStateException("Cannot open model");
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                // ggml header, multilingual vocabulary, Large v3 Turbo dimensions,
                // quantization Q5_0 (ftype 8 + quantization version * 1000).
                try (RandomAccessFile file = new RandomAccessFile(stage, "r")) {
                    int magic = Integer.reverseBytes(file.readInt());
                    int vocabulary = Integer.reverseBytes(file.readInt());
                    int context = Integer.reverseBytes(file.readInt());
                    int width = Integer.reverseBytes(file.readInt());
                    file.seek(44);
                    int ftype = Integer.reverseBytes(file.readInt());
                    if (magic != 0x67676d6c || vocabulary != 51866 || context != 1500 || width != 1280 || ftype % 1000 != 8 || stage.length() != 574041195L) throw new IllegalArgumentException("Expected complete ggml-large-v3-turbo-q5_0.bin");
                }
                java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
                try(InputStream input=new java.io.FileInputStream(stage)){byte[] bytes=new byte[131072];int n;while((n=input.read(bytes))!=-1)hash.update(bytes,0,n);}
                if(!GhostSpeechModels.hex(hash.digest()).equals(GhostSpeechModels.hash(GhostSpeechModels.TURBO)))throw new IllegalArgumentException("Whisper model integrity check failed");
                if (backup.exists() && !backup.delete()) throw new IllegalStateException("Cannot clear old model");
                if (active.exists() && !active.renameTo(backup)) throw new IllegalStateException("Cannot preserve current model");
                if (!stage.renameTo(active)) {
                    if (backup.exists()) backup.renameTo(active);
                    throw new IllegalStateException("Cannot activate model");
                }
                backup.delete();GhostSpeechModels.choose(GhostSpeechModels.TURBO);success = true;
            } catch (Throwable error) { stage.delete(); FileLog.e(error); }
            final boolean imported = success;
            AndroidUtilities.runOnUIThread(() -> {
                Toast.makeText(this, imported ? R.string.GhostTranscriptionModelImported : R.string.GhostTranscriptionModelImportFailed, Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }
}
