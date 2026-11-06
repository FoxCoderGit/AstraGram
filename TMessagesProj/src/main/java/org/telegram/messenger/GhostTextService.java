package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.utils.proxy.ProxySettings;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Filero results use Telegram's normal translated-message rendering and cache. */
public final class GhostTextService {
    public static final String CORRECT_LANGUAGE = "ghost_correct";
    private static final ExecutorService queue = Executors.newFixedThreadPool(2);
    private static final HashSet<String> loading = new HashSet<>();
    private static final HashMap<String, Long> failed = new HashMap<>();
    private static final HashSet<String> shownErrors = new HashSet<>();
    private static final Object rateLock = new Object();
    private static long nextRequestAt;
    private static volatile long rateLimitedUntil;
    private static final ArrayList<PendingTranslation> pending = new ArrayList<>();
    private static boolean flushScheduled;
    private static final android.util.LruCache<String, String> textCache = new android.util.LruCache<>(250);
    private static class PendingTranslation {
        MessageObject message;
        int account;
        long dialog, user;
        String lang, source, key;
    }
    private GhostTextService() { }
    public static boolean enabled() { return true; }
    public static String language(int account, long dialog) {
        return MessagesController.getMainSettings(account).getBoolean("ghost_correct_" + dialog, false) ? CORRECT_LANGUAGE : "ru";
    }
    public static void settings(BaseFragment fragment, long dialog) {
        int account = fragment.getCurrentAccount();
        TranslateController controller = MessagesController.getInstance(account).getTranslateController();
        String[] items = {LocaleController.getString(R.string.GhostTranslateRussian), LocaleController.getString(R.string.GhostCorrectMode), LocaleController.getString(R.string.ShowOriginalButton), LocaleController.getString(R.string.GhostFileroConnection)};
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(LocaleController.getString(R.string.TranslateMessage))
            .setMessage(LocaleController.getString(R.string.GhostFileroNotice))
            .setItems(items, (d, which) -> {
                if (which == 3) {
                    fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.GhostFileroConnection))
                        .setItems(new String[]{LocaleController.getString(R.string.GhostFileroThroughProxy), LocaleController.getString(R.string.GhostFileroDirect)}, (dd, choice) -> {
                            MessagesController.getGlobalMainSettings().edit().putBoolean("ghost_filero_direct", choice == 1).apply();
                            reset(account, dialog);
                            if (controller.isTranslatingDialog(dialog)) { controller.toggleTranslatingDialog(dialog, false); controller.toggleTranslatingDialog(dialog, true); }
                        }).create());
                } else if (which < 2) {
                    controller.toggleTranslatingDialog(dialog, false);
                    MessagesController.getMainSettings(account).edit().putBoolean("ghost_correct_" + dialog, which == 1).apply();
                    controller.toggleTranslatingDialog(dialog, true);
                } else controller.toggleTranslatingDialog(dialog, false);
            }).create());
    }
    public static void request(String text, boolean correct, BiConsumer<String, String> callback) {
        queue.execute(() -> {
            String result = null, error = null;
            try { result = transform(text, correct); }
            catch (Exception e) { error = e.getMessage(); if (error == null) error = e.getClass().getSimpleName(); }
            final String value = result, failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.accept(value, failure));
        });
    }
    public static String transform(String text, boolean correct) throws Exception {
        if (TextUtils.isEmpty(text)) return text;
        String cacheKey = correct + ":" + text;
        String cached = textCache.get(cacheKey);
        if (cached != null) return cached;
        StringBuilder result = new StringBuilder();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 5000);
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                int boundary = text.lastIndexOf('\n', end - 1);
                if (boundary > start + 2000) end = boundary + 1;
            }
            String chunk = text.substring(start, end), chunkKey = correct + ":" + chunk;
            String translatedChunk = textCache.get(chunkKey);
            if (translatedChunk == null) {
                try {
                    translatedChunk = withRetries(() -> requestChunk(chunk, correct), Thread::sleep);
                } catch (HttpStatusException error) {
                    if (!isTransientStatus(error.status)) throw error;
                    serviceUnavailableUntil = android.os.SystemClock.elapsedRealtime() + 30000;
                    throw new Exception(LocaleController.getString(R.string.GhostFileroUnavailable), error);
                }
                textCache.put(chunkKey, translatedChunk);
            }
            result.append(translatedChunk);
            start = end;
        }
        String transformed = result.toString();
        textCache.put(cacheKey, transformed);
        return transformed;
    }
    private static volatile long serviceUnavailableUntil;
    static class HttpStatusException extends Exception {
        final int status;
        HttpStatusException(int status, String message) { super(message); this.status = status; }
    }
    interface RetryOperation { String run() throws Exception; }
    interface RetryPause { void waitFor(long milliseconds) throws InterruptedException; }
    private static boolean isTransientStatus(int status) { return status == 502 || status == 503 || status == 504; }
    static String withRetries(RetryOperation operation, RetryPause pause) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return operation.run(); }
            catch (HttpStatusException error) {
                if (!isTransientStatus(error.status) || attempt >= 2) throw error;
                pause.waitFor(attempt == 0 ? 2000 : 5000);
            }
        }
    }
    public static void rewrite(String text, String style, BiConsumer<String, String> callback) {
        queue.execute(() -> {
            String value = null, error = null;
            try {
                if (!java.util.Arrays.asList("normal", "simple", "official", "short", "beautiful").contains(style)) throw new IllegalArgumentException("Unsupported style");
                if (TextUtils.isEmpty(text) || text.codePointCount(0, text.length()) > 5000) throw new IllegalArgumentException("Filero: limit 5000 characters");
                String key = "rewrite:" + style + ":" + text;
                value = textCache.get(key);
                if (value == null) {
                    JSONObject payload = new JSONObject().put("text", text).put("style", style);
                    value = withRetries(() -> postJson("rewrite-text", payload), Thread::sleep);
                    textCache.put(key, value);
                }
            } catch (Exception failure) { error = failure.getMessage(); }
            final String result = value, failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.accept(result, failure));
        });
    }
    private static String requestChunk(String text, boolean correct) throws Exception {
        JSONObject payload = new JSONObject().put("text", text);
        if (!correct) payload.put("source_lang", "auto").put("target_lang", "ru");
        return postJson(correct ? "text-corrector" : "translate", payload);
    }
    private static String postJson(String endpoint, JSONObject payload) throws Exception {
        if (android.os.SystemClock.elapsedRealtime() < serviceUnavailableUntil) throw new Exception(LocaleController.getString(R.string.GhostFileroUnavailable));
        synchronized (rateLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now < rateLimitedUntil) throw new HttpStatusException(429, LocaleController.getString(R.string.GhostFileroRateLimited));
            long pause = nextRequestAt - now;
            if (pause > 0) Thread.sleep(pause);
            if (android.os.SystemClock.elapsedRealtime() < rateLimitedUntil) throw new HttpStatusException(429, LocaleController.getString(R.string.GhostFileroRateLimited));
            nextRequestAt = android.os.SystemClock.elapsedRealtime() + 1500;
        }
        if (android.os.SystemClock.elapsedRealtime() < serviceUnavailableUntil) throw new Exception(LocaleController.getString(R.string.GhostFileroUnavailable));
        URL url = new URL("https://filero.ru/api/" + endpoint);
        Proxy proxy = Proxy.NO_PROXY;
        if (SharedConfig.isProxyEnabled() && !MessagesController.getGlobalMainSettings().getBoolean("ghost_filero_direct", false)) {
            ProxySettings settings = SharedConfig.currentProxy.settings;
            if (settings.getType() != ProxySettings.Type.SOCKS5 || !settings.getUser().isEmpty())
                throw new Exception(LocaleController.getString(R.string.GhostFileroProxyUnsupported));
            proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(settings.getAddress(), settings.getPort()));
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            if (status == 429) {
                int retrySeconds = 30;
                try { retrySeconds = Math.max(5, Math.min(120, Integer.parseInt(connection.getHeaderField("Retry-After")))); } catch (Exception ignored) { }
                rateLimitedUntil = android.os.SystemClock.elapsedRealtime() + retrySeconds * 1000L;
                throw new HttpStatusException(429, LocaleController.getString(R.string.GhostFileroRateLimited));
            }
            if (status < 200 || status >= 300) throw new HttpStatusException(status, "Filero: HTTP " + status);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) >= 0) {
                    if (output.size() + n > 1024 * 1024) throw new Exception("Filero: response too large");
                    output.write(buffer, 0, n);
                }
            }
            JSONObject response = new JSONObject(output.toString("UTF-8"));
            if (!response.optBoolean("ok") || !(response.opt("result") instanceof String))
                throw new Exception(response.optString("error", "Filero: invalid response"));
            String transformed = response.getString("result");
            if (TextUtils.isEmpty(transformed)) throw new Exception("Filero: empty response");
            return transformed;
        } finally { connection.disconnect(); }
    }
    private static String marker(String nonce, int index) { return "gg_" + nonce + "_" + index; }
    public static List<String> transformBatch(List<String> texts, boolean correct) throws Exception {
        return transformBatch(texts, correct, GhostTextService::transform);
    }
    interface TextTransform { String apply(String text, boolean correct) throws Exception; }
    static List<String> transformBatch(List<String> texts, boolean correct, TextTransform transport) throws Exception {
        if (texts.isEmpty()) return new ArrayList<>();
        ArrayList<String> cachedResults = new ArrayList<>();
        for (String text : texts) {
            String cached = textCache.get(correct + ":" + text);
            if (cached == null) { cachedResults.clear(); break; }
            cachedResults.add(cached);
        }
        if (!cachedResults.isEmpty()) return cachedResults;
        if (texts.size() == 1) {
            String value = transport.apply(texts.get(0), correct);
            textCache.put(correct + ":" + texts.get(0), value);
            return java.util.Collections.singletonList(value);
        }
        String nonce = java.util.UUID.randomUUID().toString().substring(0, 8);
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            String tag = marker(nonce, i);
            payload.append('<').append(tag).append('>').append(texts.get(i)).append("</").append(tag).append(">\n");
        }
        if (payload.length() > 5000) throw new IllegalArgumentException("Filero batch exceeds 5000 characters");
        String response = transport.apply(payload.toString(), correct);
        List<String> translated;
        try {
            translated = unpackBatch(response, nonce, texts.size());
        } catch (Exception boundaryError) {
            // HTTP errors never reach this branch. Only ambiguous successful responses are split.
            int middle = texts.size() / 2;
            ArrayList<String> recovered = new ArrayList<>(transformBatch(texts.subList(0, middle), correct, transport));
            recovered.addAll(transformBatch(texts.subList(middle, texts.size()), correct, transport));
            return recovered;
        }
        for (int i = 0; i < texts.size(); i++) textCache.put(correct + ":" + texts.get(i), translated.get(i));
        return translated;
    }
    public static List<String> unpackBatch(String response, String nonce, int count) throws Exception {
        // Filero occasionally wraps the answer in Markdown or reformats marker casing/spacing.
        response = response.trim();
        if (response.startsWith("```") && response.endsWith("```")) {
            int newline = response.indexOf('\n');
            if (newline >= 0) response = response.substring(newline + 1, response.length() - 3).trim();
        }
        java.util.regex.Pattern boundaries = java.util.regex.Pattern.compile(
            "(?:<|&lt;)\\s*(/?)\\s*gg_" + java.util.regex.Pattern.quote(nonce) + "_(\\d+)\\s*(?:>|&gt;)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = boundaries.matcher(response);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement("<" + matcher.group(1) + marker(nonce, Integer.parseInt(matcher.group(2))) + ">"));
        matcher.appendTail(normalized); response = normalized.toString();
        ArrayList<String> results = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            String tag = marker(nonce, i), open = "<" + tag + ">", close = "</" + tag + ">";
            int start = response.indexOf(open, cursor);
            if (start < 0 || !response.substring(cursor, start).trim().isEmpty()) throw new Exception("Filero: invalid message boundaries");
            int end = i + 1 == count ? response.length() : response.indexOf("<" + marker(nonce, i + 1) + ">", start + open.length());
            if (end < 0) throw new Exception("Filero: invalid message boundaries");
            String text = response.substring(start + open.length(), end).trim();
            // The correction endpoint can omit closing XML tags while retaining all numbered headers.
            int closing = text.indexOf(close);
            if (closing >= 0) {
                if (!text.substring(closing + close.length()).trim().isEmpty()) throw new Exception("Filero: invalid message boundaries");
                text = text.substring(0, closing).trim();
            }
            if (text.contains("<gg_" + nonce + "_") || text.contains("</gg_" + nonce + "_")) throw new Exception("Filero: invalid message boundaries");
            if (text.trim().isEmpty()) throw new Exception("Filero: empty message in batch");
            results.add(text); cursor = end;
        }
        if (!response.substring(cursor).trim().isEmpty()) throw new Exception("Filero: invalid message boundaries");
        return results;
    }
    private static void flushPending() {
        flushScheduled = false;
        LinkedHashMap<String, ArrayList<PendingTranslation>> groups = new LinkedHashMap<>();
        for (PendingTranslation item : pending) {
            if (!MessagesController.getInstance(item.account).getTranslateController().isTranslatingDialog(item.dialog)
                || !item.lang.equals(language(item.account, item.dialog))
                || item.user != UserConfig.getInstance(item.account).getClientUserId()) { loading.remove(item.key); continue; }
            String group = item.account + ":" + item.dialog + ":" + item.lang;
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(item);
        }
        pending.clear();
        for (ArrayList<PendingTranslation> group : groups.values()) {
            ArrayList<PendingTranslation> batch = new ArrayList<>();
            int length = 0;
            for (PendingTranslation item : group) {
                // Includes two XML boundaries, the nonce, index and a newline.
                int cost = item.source.length() + 48;
                if (!batch.isEmpty() && length + cost > 5000) { dispatchBatch(batch); batch = new ArrayList<>(); length = 0; }
                batch.add(item); length += cost;
                if (cost > 5000) { dispatchBatch(batch); batch = new ArrayList<>(); length = 0; }
            }
            if (!batch.isEmpty()) dispatchBatch(batch);
        }
    }
    private static void dispatchBatch(ArrayList<PendingTranslation> batch) {
        queue.execute(() -> {
            PendingTranslation first = batch.get(0);
            if (!GhostOptionalFeatures.enabled(GhostOptionalFeatures.CHAT_TRANSLATION)
                || !MessagesController.getMainSettings(first.account).getBoolean("ghost_translate_active_" + first.dialog, true)
                || !first.lang.equals(language(first.account, first.dialog))
                || first.user != UserConfig.getInstance(first.account).getClientUserId()) {
                AndroidUtilities.runOnUIThread(() -> { for (PendingTranslation item : batch) loading.remove(item.key); });
                return;
            }
            List<String> values = null; String error = null;
            LinkedHashMap<String, Integer> indexes = new LinkedHashMap<>();
            try {
                ArrayList<String> texts = new ArrayList<>();
                for (PendingTranslation item : batch) {
                    if (!indexes.containsKey(item.source)) { indexes.put(item.source, texts.size()); texts.add(item.source); }
                }
                values = transformBatch(texts, CORRECT_LANGUAGE.equals(batch.get(0).lang));
            } catch (Exception e) { error = e.getMessage(); if (error == null) error = e.getClass().getSimpleName(); }
            final List<String> results = values; final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                for (PendingTranslation item : batch) {
                    String value = results == null ? textCache.get(CORRECT_LANGUAGE.equals(item.lang) + ":" + item.source) : results.get(indexes.get(item.source));
                    complete(item, value, value == null ? failure : null);
                }
            });
        });
    }
    public static void check(MessageObject message, boolean onScreen) {
        if (!GhostOptionalFeatures.enabled(GhostOptionalFeatures.CHAT_TRANSLATION)) return;
        if (!onScreen || message == null || message.messageOwner == null || TextUtils.isEmpty(message.messageOwner.message)) return;
        int account = message.currentAccount; long dialog = message.getDialogId();
        TranslateController controller = MessagesController.getInstance(account).getTranslateController();
        if (!controller.isTranslatingDialog(dialog)) return;
        String lang = language(account, dialog), source = message.messageOwner.message;
        long user = UserConfig.getInstance(account).getClientUserId();
        if (message.messageOwner.translatedText != null && lang.equals(message.messageOwner.translatedToLanguage)) return;
        String key = account + ":" + dialog + ":" + message.getId() + ":" + lang + ":" + source;
        if (loading.contains(key) || failed.containsKey(key)) return;
        loading.add(key);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslating, message);
        PendingTranslation item = new PendingTranslation(); item.message = message; item.account = account; item.dialog = dialog;
        item.user = user; item.lang = lang; item.source = source; item.key = key;
        String cached = textCache.get(CORRECT_LANGUAGE.equals(lang) + ":" + source);
        if (cached != null) { complete(item, cached, null); return; }
        pending.add(item);
        if (!flushScheduled) { flushScheduled = true; AndroidUtilities.runOnUIThread(GhostTextService::flushPending, 500); }
    }
    private static void complete(PendingTranslation item, String text, String error) {
            MessageObject message = item.message;
            int account = item.account; long dialog = item.dialog; String lang = item.lang;
            loading.remove(item.key);
            if (!GhostOptionalFeatures.enabled(GhostOptionalFeatures.CHAT_TRANSLATION)) return;
            if (item.user != UserConfig.getInstance(account).getClientUserId() || !item.source.equals(message.messageOwner.message) || !lang.equals(language(account, dialog))) return;
            if (error == null) {
                TLRPC.TL_textWithEntities translated = new TLRPC.TL_textWithEntities(); translated.text = text;
                message.messageOwner.translatedText = translated;
                message.messageOwner.translatedToLanguage = lang;
                message.translated = false;
                MessagesStorage.getInstance(account).updateMessageCustomParams(dialog, message.messageOwner);
            } else {
                if (failed.size() > 500) failed.clear(); failed.put(item.key, System.currentTimeMillis());
                if (shownErrors.add(account + ":" + dialog)) NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, org.telegram.ui.Components.Bulletin.TYPE_ERROR, error);
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, message);
    }
    public static boolean isLoading(MessageObject message) {
        if (message == null || message.messageOwner == null) return false;
        return loading.contains(message.currentAccount + ":" + message.getDialogId() + ":" + message.getId() + ":" + language(message.currentAccount, message.getDialogId()) + ":" + message.messageOwner.message);
    }
    public static void reset(int account, long dialog) {
        String prefix = account + ":" + dialog + ":";
        failed.keySet().removeIf(key -> key.startsWith(prefix)); shownErrors.remove(account + ":" + dialog);
    }
}
