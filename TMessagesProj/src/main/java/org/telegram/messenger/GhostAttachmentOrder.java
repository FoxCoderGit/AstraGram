package org.telegram.messenger;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Device-wide attachment order. Missing/temporarily disabled actions keep their place. */
public final class GhostAttachmentOrder {
    private static final String KEY = "ghost_attachment_order_v1";
    public static List<String> read() {
        ArrayList<String> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(MessagesController.getGlobalMainSettings().getString(KEY, "[]"));
            for (int i=0;i<values.length();i++) if (!result.contains(values.getString(i))) result.add(values.getString(i));
        } catch (Exception e) { FileLog.e(e); }
        return result;
    }
    public static void save(List<String> visible) {
        List<String> merged = read();
        // Replace visible slots only; hidden actions and future additions survive reordering.
        int next=0;
        for(int i=0;i<merged.size();i++) if(visible.contains(merged.get(i))) merged.set(i,visible.get(next++));
        while(next<visible.size()) merged.add(visible.get(next++));
        MessagesController.getGlobalMainSettings().edit().putString(KEY,new JSONArray(merged).toString()).apply();
    }
    private GhostAttachmentOrder() {}
}
