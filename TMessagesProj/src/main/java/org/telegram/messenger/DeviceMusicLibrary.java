package org.telegram.messenger;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** App-local metadata snapshot for the device's indexed audio. Views own their send/playback objects. */
public final class DeviceMusicLibrary {
    private static final DeviceMusicLibrary INSTANCE = new DeviceMusicLibrary();

    public static DeviceMusicLibrary getInstance() { return INSTANCE; }

    public interface Listener {
        void onTracks(List<Track> tracks, boolean loading);
    }

    public static final class Track {
        public final long id;
        public final String artist, title, path, album, filename, mime;
        public final int duration;
        public final long size, modified;
        private String artistKey, titleKey, filenameKey;
        private String artistLatin, titleLatin, filenameLatin;

        Track(Cursor cursor) {
            id = cursor.getLong(0);
            artist = "<unknown>".equals(cursor.getString(1)) ? "" : value(cursor.getString(1));
            path = value(cursor.getString(3));
            duration = (int) Math.min(Integer.MAX_VALUE, Math.max(0, cursor.getLong(4) / 1000));
            album = value(cursor.getString(5));
            String displayName = value(cursor.getString(6));
            filename = displayName.isEmpty() ? new File(path).getName() : displayName;
            String mediaTitle = value(cursor.getString(2));
            title = mediaTitle.isEmpty() ? filename : mediaTitle;
            String type = value(cursor.getString(7));
            mime = type.startsWith("audio/") ? type : "audio/mpeg";
            size = Math.max(0, cursor.getLong(8));
            modified = cursor.getLong(9);
        }

        private void indexSearchKeys() {
            artistKey = artist.toLowerCase(Locale.ROOT);
            titleKey = title.toLowerCase(Locale.ROOT);
            filenameKey = filename.toLowerCase(Locale.ROOT);
            artistLatin = AndroidUtilities.translitSafe(artistKey);
            titleLatin = AndroidUtilities.translitSafe(titleKey);
            filenameLatin = AndroidUtilities.translitSafe(filenameKey);
        }

        boolean sameFile(Track other) {
            return id == other.id && size == other.size && modified == other.modified && duration == other.duration
                    && title.equals(other.title) && artist.equals(other.artist) && album.equals(other.album)
                    && path.equals(other.path) && filename.equals(other.filename) && mime.equals(other.mime);
        }

        public boolean matches(String query, String latin) {
            return titleKey.contains(query) || artistKey.contains(query) || filenameKey.contains(query)
                    || (!latin.isEmpty() && (titleLatin.contains(latin) || artistLatin.contains(latin) || filenameLatin.contains(latin)));
        }

        public Entry createEntry(int account, int messageId) {
            return new Entry(this, account, messageId);
        }

        private MessageObject createMessage(int account, int messageId) {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.out = true;
            message.id = messageId;
            message.peer_id = new TLRPC.TL_peerUser();
            message.from_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = message.from_id.user_id = UserConfig.getInstance(account).getClientUserId();
            message.date = (int) (System.currentTimeMillis() / 1000);
            message.message = "";
            message.attachPath = path;
            message.media = new TLRPC.TL_messageMediaDocument();
            message.media.flags |= 3;
            TLRPC.TL_document document = new TLRPC.TL_document();
            message.media.document = document;
            message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
            document.file_reference = new byte[0];
            document.date = message.date;
            document.mime_type = mime;
            document.size = size;
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.duration = duration;
            audio.title = title;
            audio.performer = artist;
            audio.flags |= 3;
            document.attributes.add(audio);
            TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
            name.file_name = filename;
            document.attributes.add(name);
            return new MessageObject(account, message, false, true);
        }
    }

    public static final class Entry extends MediaController.AudioEntry {
        private final Track track;
        private final int account;
        private final int messageId;
        private String query;

        private Entry(Track track, int account, int messageId) {
            this.track = track;
            this.account = account;
            this.messageId = messageId;
            id = messageId;
            author = track.artist;
            title = track.title;
            path = track.path;
            duration = track.duration;
            genre = track.album;
        }

        public void setSearchQuery(String query) {
            this.query = query;
            if (messageObject != null) messageObject.setQuery(query);
        }

        public MessageObject resolveMessage() {
            if (messageObject == null) {
                messageObject = track.createMessage(account, messageId);
                messageObject.setQuery(query);
            }
            return messageObject;
        }
    }

    private static final class Volume {
        final String version;
        final long generation;
        final HashMap<Long, Track> tracks;

        Volume(String version, long generation, HashMap<Long, Track> tracks) {
            this.version = version;
            this.generation = generation;
            this.tracks = tracks;
        }
    }

    private final DispatchQueue indexQueue = new DispatchQueue("DeviceMusicIndex");
    private final DispatchQueue artworkQueue = new DispatchQueue("DeviceMusicArtwork");
    private final HashMap<String, Volume> volumes = new HashMap<>();
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private List<Track> snapshot = Collections.emptyList();
    private boolean ready, busy, refreshAgain, observing;
    private long permissionEpoch;
    private final Runnable refresh = this::refresh;
    private final LruCache<String, Bitmap> artwork = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final LruCache<String, Boolean> unavailableArtwork = new LruCache<>(256);

    private DeviceMusicLibrary() {}

    private static String value(String string) { return string == null ? "" : string; }

    private static boolean canRead(Context context) {
        return context.checkSelfPermission(Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Subscriptions and snapshot delivery are confined to the main thread.
    public void subscribe(Listener listener) {
        if (!listeners.add(listener)) return;
        Context context = ApplicationLoader.applicationContext;
        if (!observing) {
            try {
                context.getContentResolver().registerContentObserver(Uri.parse("content://media"), true,
                        new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override public void onChange(boolean selfChange) { onMediaChanged(); }

                            @Override public void onChange(boolean selfChange, Uri uri) {
                                List<String> segments = uri == null ? Collections.emptyList() : uri.getPathSegments();
                                if (segments.size() < 2 || segments.contains("audio") || segments.contains("file")) onMediaChanged();
                            }
                        });
                observing = true;
            } catch (RuntimeException error) {
                FileLog.e(error);
            }
        }
        if (!canRead(context)) {
            clearForMissingPermission();
            return;
        }
        listener.onTracks(snapshot, !ready);
        refresh();
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    private void onMediaChanged() {
        if (listeners.isEmpty()) return;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.runOnUIThread(refresh, 220);
    }

    private void clearForMissingPermission() {
        permissionEpoch++;
        ready = false;
        snapshot = Collections.emptyList();
        artwork.evictAll();
        unavailableArtwork.evictAll();
        indexQueue.postRunnable(volumes::clear);
        for (Listener listener : new ArrayList<>(listeners)) listener.onTracks(snapshot, false);
    }

    private void refresh() {
        if (listeners.isEmpty()) return;
        Context context = ApplicationLoader.applicationContext;
        if (!canRead(context)) {
            clearForMissingPermission();
            return;
        }
        if (busy) {
            refreshAgain = true;
            return;
        }
        busy = true;
        long requestEpoch = permissionEpoch;
        indexQueue.postRunnable(() -> {
            List<Track> result = null;
            try {
                result = readIndex(context);
            } catch (RuntimeException error) {
                FileLog.e(error);
            }
            List<Track> tracks = result;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (!canRead(context)) {
                    clearForMissingPermission();
                    refreshAgain = false;
                    return;
                }
                if (requestEpoch == permissionEpoch && (tracks != null || !ready)) {
                    boolean changed = !ready || (tracks != null && !snapshot.equals(tracks));
                    if (tracks != null) {
                        snapshot = tracks;
                        ready = true;
                    }
                    if (changed) for (Listener listener : new ArrayList<>(listeners)) listener.onTracks(snapshot, false);
                }
                if (refreshAgain) {
                    refreshAgain = false;
                    refresh();
                }
            });
        });
    }

    private List<Track> readIndex(Context context) {
        boolean versioned = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        Set<String> names = versioned ? MediaStore.getExternalVolumeNames(context) : Collections.singleton("external");
        HashMap<String, Volume> loaded = new HashMap<>();
        ArrayList<Track> tracks = new ArrayList<>();
        for (String name : names) {
            String version = versioned ? MediaStore.getVersion(context, name) : "";
            long generation = versioned ? MediaStore.getGeneration(context, name) : -1;
            Volume old = volumes.get(name);
            boolean sameVersion = old != null && Objects.equals(old.version, version);
            Volume volume = versioned && sameVersion && old.generation == generation
                    ? old : new Volume(version, generation, readVolume(context.getContentResolver(), name, sameVersion ? old : null));
            loaded.put(name, volume);
            tracks.addAll(volume.tracks.values());
        }
        volumes.clear();
        volumes.putAll(loaded);
        tracks.sort(Comparator.comparing((Track track) -> track.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(track -> track.artist, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(track -> track.path));
        return Collections.unmodifiableList(tracks);
    }

    private HashMap<Long, Track> readVolume(ContentResolver resolver, String volume, Volume old) {
        Uri uri = MediaStore.Audio.Media.getContentUri(volume);
        String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED};
        HashMap<Long, Track> result = new HashMap<>();
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 OR (" + MediaStore.Audio.Media.DURATION
                + " > 0 AND " + MediaStore.Audio.Media.MIME_TYPE + " LIKE 'audio/%' AND "
                + MediaStore.Audio.Media.IS_RINGTONE + " = 0 AND " + MediaStore.Audio.Media.IS_NOTIFICATION
                + " = 0 AND " + MediaStore.Audio.Media.IS_ALARM + " = 0)";
        try (Cursor cursor = resolver.query(uri, columns, selection, null, null)) {
            if (cursor == null) throw new IllegalStateException("Audio index unavailable");
            while (cursor.moveToNext()) {
                if (cursor.isNull(3) || cursor.getString(3).isEmpty()) continue;
                Track track = new Track(cursor);
                Track cached = old == null ? null : old.tracks.get(track.id);
                if (cached != null && cached.sameFile(track)) {
                    track = cached;
                } else {
                    track.indexSearchKeys();
                }
                result.put(track.id, track);
            }
        }
        return result;
    }

    public void requestArtwork(String path, Utilities.Callback<Bitmap> callback) {
        artworkQueue.postRunnable(() -> {
            if (!canRead(ApplicationLoader.applicationContext)) return;
            File file = new File(path);
            String key = path + ":" + file.lastModified() + ":" + file.length();
            Bitmap image = artwork.get(key);
            if (image == null && unavailableArtwork.get(key) == null) {
                image = readArtwork(file);
                if (image == null) unavailableArtwork.put(key, true);
                else artwork.put(key, image);
            }
            Bitmap result = image;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    private Bitmap readArtwork(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            byte[] encoded = retriever.getEmbeddedPicture();
            if (encoded == null || encoded.length > 8 * 1024 * 1024) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            int side = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (side / options.inSampleSize > 192) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        } catch (RuntimeException error) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }
}
