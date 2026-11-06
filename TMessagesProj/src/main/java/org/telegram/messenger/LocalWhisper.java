package org.telegram.messenger;

import android.text.TextUtils;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranscribeButton;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/** Offline Russian voice/video transcription with selectable local engines. */
public final class LocalWhisper {
    private static final String MODEL_NAME = "ggml-large-v3-turbo-q5_0.bin";
    private static final DispatchQueue queue = new DispatchQueue("localWhisper");
    private static final int THREADS = 4;
    private static final ConcurrentHashMap<String, Boolean> operations = new ConcurrentHashMap<>();

    private LocalWhisper() { }

    public static File getModelDir() {
        return ApplicationLoader.getFilesDirFixed("whisper");
    }

    public static File getModelFile() {
        return new File(getModelDir(), MODEL_NAME);
    }

    public static boolean isAvailable() {
        return GhostSpeechModels.available(GhostSpeechModels.resolve());
    }

    public static boolean isSupported(MessageObject messageObject) {
        return messageObject != null && (messageObject.isVoice() || messageObject.isRoundVideo()) && isAvailable();
    }

    /** Allows the stock button to be shown for every ordinary voice message. */
    public static boolean canOffer(MessageObject messageObject) {
        return GhostSpeechModels.choice() != GhostSpeechModels.TELEGRAM && messageObject != null && (messageObject.isVoice() || messageObject.isRoundVideo());
    }

    public static void importModel(Context context) {
        try {
            Intent intent = new Intent(context, WhisperModelImportActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean transcribe(MessageObject messageObject) {
        if (!GhostLicenseBridge.allowed()) return false;
        if (!isSupported(messageObject) || messageObject.messageOwner == null) return false;
        final int engine=GhostSpeechModels.resolve();
        final TLRPC.Message owner = messageObject.messageOwner;
        if (owner.voiceTranscriptionFinal && !TextUtils.isEmpty(owner.voiceTranscription)) {
            owner.voiceTranscriptionOpen = true;
            TranscribeButton.openVideoTranscription(messageObject);
            MessagesStorage.getInstance(messageObject.currentAccount).updateMessageVoiceTranscriptionOpen(messageObject.getDialogId(), messageObject.getId(), owner);
            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, true, true);
            return true;
        }
        final String operationKey = messageObject.currentAccount + ":" + messageObject.getDialogId() + ":" + messageObject.getId();
        if (operations.putIfAbsent(operationKey, Boolean.TRUE) != null) {
            return true;
        }
        TranscribeButton.beginLocalTranscription(messageObject);
        owner.voiceTranscriptionOpen = true;
        owner.voiceTranscriptionFinal = false;
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject);
        File source = findSource(messageObject);
        if (source != null && source.isFile()) {
            startInference(messageObject, operationKey, source,engine);
        } else {
            new PendingDownload(messageObject, operationKey,engine).start();
        }
        return true;
    }

    private static File findSource(MessageObject message) {
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            File local = new File(message.messageOwner.attachPath);
            if (local.isFile()) return local;
        }
        return FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
    }

    /** FileLoader notifications arrive on the UI thread, as does the button click. */
    private static final class PendingDownload implements NotificationCenter.NotificationCenterDelegate {
        final MessageObject message;
        final String operationKey;
        final NotificationCenter notifications;
        final String fileName;
        final int engine;
        final Runnable timeout = () -> finish(null);
        boolean finished;

        PendingDownload(MessageObject message, String key,int engine) {
            this.engine=engine;
            this.message = message;
            operationKey = key;
            notifications = NotificationCenter.getInstance(message.currentAccount);
            fileName = FileLoader.getAttachFileName(message.getDocument());
        }

        void start() {
            TLRPC.Document document = message.getDocument();
            if (document == null || TextUtils.isEmpty(fileName)) { finish(null); return; }
            notifications.addObserver(this, NotificationCenter.fileLoaded);
            notifications.addObserver(this, NotificationCenter.fileLoadFailed);
            AndroidUtilities.runOnUIThread(timeout, 120000);
            try {
                // Register before requesting: an existing download can finish immediately.
                FileLoader.getInstance(message.currentAccount).loadFile(document, message, FileLoader.PRIORITY_HIGH, 0);
                File local = findSource(message);
                if (local != null && local.isFile()) finish(local);
            } catch (Throwable error) {
                FileLog.e(error);
                finish(null);
            }
        }

        @Override public void didReceivedNotification(int id, int account, Object... args) {
            if (account != message.currentAccount || args.length == 0 || !fileName.equals(args[0])) return;
            if (id == NotificationCenter.fileLoaded) {
                File source = args.length > 1 && args[1] instanceof File ? (File) args[1] : findSource(message);
                finish(source != null && source.isFile() ? source : null);
            } else if (id == NotificationCenter.fileLoadFailed) {
                finish(null);
            }
        }

        void finish(File source) {
            if (finished) return;
            finished = true;
            notifications.removeObserver(this, NotificationCenter.fileLoaded);
            notifications.removeObserver(this, NotificationCenter.fileLoadFailed);
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (source != null) {
                startInference(message, operationKey, source,engine);
            } else {
                operations.remove(operationKey);
                message.messageOwner.voiceTranscriptionOpen = false;
                message.messageOwner.voiceTranscriptionFinal = false;
                TranscribeButton.finishLocalTranscription(message, null);
                FileLog.d("Local Whisper: download did not complete; button can be retried");
            }
        }
    }

    private static void startInference(MessageObject messageObject, String operationKey, File source,int engine) {
        final TLRPC.Message owner = messageObject.messageOwner;
        FileLog.d("Local Whisper: starting transcription");
        queue.postRunnable(() -> {
            String result;
            try {
                result = transcribeFile(source,engine);
            } catch (Throwable e) {
                FileLog.e("Local Whisper: transcription failed", e);
                result = "ERROR: " + e.getMessage();
            }
            final String text = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (operations.remove(operationKey) == null) return;
                if (text == null || text.startsWith("ERROR:") || TextUtils.isEmpty(text.trim())) {
                    FileLog.e("Local Whisper: transcription failed: " + text);
                    owner.voiceTranscriptionOpen = false;
                    owner.voiceTranscriptionFinal = false;
                    TranscribeButton.finishLocalTranscription(messageObject, null);
                    int error = text != null && text.contains("TIMEOUT")
                            ? R.string.GhostTranscriptionTimeout
                            : R.string.GhostTranscriptionNoSpeech;
                    if (text != null && !text.contains("TIMEOUT") && !text.contains("NO_SPEECH")) {
                        Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(ApplicationLoader.applicationContext, error, Toast.LENGTH_LONG).show();
                    }
                } else {
                    FileLog.d("Local Whisper: transcription finished, chars=" + text.length());
                    owner.voiceTranscription = text.trim();
                    owner.voiceTranscriptionOpen = true;
                    owner.voiceTranscriptionFinal = true;
                    TranscribeButton.openVideoTranscription(messageObject);
                    TranscribeButton.finishLocalTranscription(messageObject, owner.voiceTranscription);
                }
            });
        });
    }

    /** Uses the same decoder for voice notes and the audio track of round videos. */
    public static String transcribeFile(File audio) {
        return transcribeFile(audio,GhostSpeechModels.resolve());
    }

    public static String transcribeFile(File audio,int engine) {
        if (!GhostLicenseBridge.allowed()) return "ERROR: GHOST_LICENSE_REQUIRED";
        if(engine==GhostSpeechModels.GROQ){
            try{return GhostGroq.transcribe(audio);}catch(Exception error){return "ERROR: "+GhostGroqSettings.friendly(error);}
        }
        File model;
        try { model=GhostSpeechModels.prepare(engine); }
        catch (Exception error) { FileLog.e("Local Whisper: cannot prepare model", error); return "ERROR: " + error.getMessage(); }
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
        long started=android.os.SystemClock.elapsedRealtime();
        String result=engine==GhostSpeechModels.VOSK?transcribeVoskNative(model.getAbsolutePath(),audio.getAbsolutePath()):transcribeNative(model.getAbsolutePath(), audio.getAbsolutePath(), THREADS, "ru");
        if(engine==GhostSpeechModels.VOSK&&result!=null&&!result.startsWith("ERROR:")){
            try{org.json.JSONArray segments=new org.json.JSONArray(result);StringBuilder text=new StringBuilder();for(int i=0;i<segments.length();i++){String value=segments.getJSONObject(i).optString("text","");if(!value.isEmpty()){if(text.length()>0)text.append(' ');text.append(value);}}result=text.length()==0?"ERROR:NO_SPEECH":text.toString();}
            catch(Exception error){FileLog.e(error);result="ERROR:VOSK_RESULT";}
        }
        long elapsed=android.os.SystemClock.elapsedRealtime()-started;
        if(result!=null&&(!result.startsWith("ERROR:")||result.contains("TIMEOUT"))){
            android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();
            try{metadata.setDataSource(audio.getAbsolutePath());double seconds=Double.parseDouble(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))/1000.0;GhostSpeechModels.record(engine,elapsed,seconds,result.contains("TIMEOUT"));}catch(Exception ignored){}finally{try{metadata.release();}catch(Exception ignored){}}
        }
        return result;
    }

    private static native String transcribeNative(String model, String audio, int threads, String language);
    private static native String transcribeVoskNative(String model,String audio);
}
