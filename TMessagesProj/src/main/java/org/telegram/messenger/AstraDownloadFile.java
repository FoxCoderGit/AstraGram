package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import java.io.File;

/** Attachment identity shared by the download queue, saved history and screen. */
public final class AstraDownloadFile {
    public final MessageObject message;
    public final TLRPC.Document document;
    public final TLRPC.Photo photo;
    public final TLRPC.PhotoSize size;
    public final long id;
    public final int dc;
    public final String key;
    public final String name;
    public final long total;

    private AstraDownloadFile(MessageObject message, TLRPC.Document document, TLRPC.Photo photo, TLRPC.PhotoSize size) {
        this.message = message;
        this.document = document;
        this.photo = photo;
        this.size = size;
        id = document != null ? document.id : photo.id;
        dc = document != null ? document.dc_id : -photo.dc_id - 1;
        key = document != null ? FileLoader.getAttachFileName(document) : FileLoader.getAttachFileName(size);
        String originalName = document != null ? FileLoader.getDocumentFileName(document) : "";
        name = originalName.isEmpty() ? key : originalName;
        total = Math.max(0, document != null ? document.size : size.size);
    }

    public static AstraDownloadFile from(MessageObject message) {
        if (message == null) return null;
        TLRPC.Document document = message.getDocument();
        if (document != null) return new AstraDownloadFile(message, document, null, null);
        TLRPC.Photo photo = message.getPhoto();
        if (photo == null) return null;
        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
        return size == null || size.location == null ? null : new AstraDownloadFile(message, null, photo, size);
    }

    public boolean isPhoto() {
        return document == null || (document.mime_type != null && document.mime_type.startsWith("image/") && !message.isGif());
    }

    public boolean isVideo() {
        return message.isVideo() || message.isRoundVideo() || (document != null && document.mime_type != null && document.mime_type.startsWith("video/"));
    }

    public String mime() {
        return document == null ? "image/jpeg" : document.mime_type == null ? "application/octet-stream" : document.mime_type;
    }

    public File localFile(FileLoader loader) {
        if (document != null) return loader.getPathToMessage(message.messageOwner);
        File path = loader.getPathToAttach(size);
        return path.isFile() ? path : loader.getLocalFile(ImageLocation.getForPhoto(size, photo));
    }

    public void load(FileLoader loader, int priority) {
        message.putInDownloadsStore = true;
        message.loadingCancelled = false;
        int cacheType = message.shouldEncryptPhotoOrVideo() ? ImageLoader.CACHE_TYPE_ENCRYPTED : 0;
        if (document != null) loader.loadFile(document, message, priority, cacheType);
        else loader.loadFile(ImageLocation.getForPhoto(size, photo), message, "jpg", priority, cacheType);
    }

    public void cancel(FileLoader loader) {
        if (document != null) loader.cancelLoadFile(document, false);
        else loader.cancelLoadFile(size, false);
    }
}
