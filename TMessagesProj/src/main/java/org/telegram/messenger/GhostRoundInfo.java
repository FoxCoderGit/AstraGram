package org.telegram.messenger;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/** Built-in round video report; reads at most 4 KiB from an already downloaded file. */
public final class GhostRoundInfo {
    public static void show(BaseFragment fragment, MessageObject message) {
        if (message == null || !message.isRoundVideo() || fragment.getParentActivity() == null) return;
        TLRPC.Document document = message.getDocument();
        if (document == null) return;
        String filename = FileLoader.getDocumentFileName(document);
        Utilities.globalQueue.postRunnable(() -> {
            byte[] header = null;
            File file = message.messageOwner.attachPath == null ? null : new File(message.messageOwner.attachPath);
            if (file == null || !file.isFile()) file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
            if (file != null && file.isFile()) {
                try (FileInputStream stream = new FileInputStream(file)) {
                    byte[] data = new byte[4096];
                    int count = stream.read(data);
                    if (count > 0) header = Arrays.copyOf(data, count);
                } catch (Exception e) { FileLog.e(e); }
            }
            String tag = GhostRoundMetadata.tag(filename, header);
            StringBuilder text = new StringBuilder("≈" + tag);
            text.append("\n\n").append(LocaleController.getString(R.string.GhostClientUnknown));
            if (filename != null && !filename.isEmpty()) text.append("\n\n").append(filename);
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    text.append("\n").append(attribute.w).append(" × ").append(attribute.h);
                    text.append("\n").append(AndroidUtilities.formatShortDuration((int) Math.ceil(attribute.duration)));
                }
            }
            if (header == null) text.append("\n\n").append(LocaleController.getString(R.string.GhostRoundMetadataNotDownloaded));
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null || fragment.getParentActivity().isFinishing()) return;
                fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.GhostRoundSource)).setMessage(text)
                        .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
            });
        });
    }
}
