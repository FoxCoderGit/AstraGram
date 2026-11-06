package org.telegram.messenger;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/** Native themed dialog; lookup is offline and runs outside the UI thread. */
public final class GhostPhoneInfo {
    private static final DispatchQueue queue = new DispatchQueue("ghostPhoneInfo");

    public static void show(BaseFragment fragment, String phone, int account) {
        if (fragment.getParentActivity() == null || phone == null || phone.trim().isEmpty()) return;
        TLRPC.User own = UserConfig.getInstance(account).getCurrentUser();
        String ownPhone = own == null || own.phone == null ? "" : own.phone;
        AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.GhostPhoneInfo))
                .setMessage(LocaleController.getString(R.string.Loading))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .setNeutralButton(LocaleController.getString(R.string.CopyNumber), (d, which) -> AndroidUtilities.addToClipboard(phone)).create();
        fragment.showDialog(dialog);
        queue.postRunnable(() -> {
            String message;
            try {
                String data = (String) Class.forName("org.ghostgram.messenger.PhoneInfoHost")
                        .getMethod("lookup", Context.class, String.class, String.class)
                        .invoke(null, ApplicationLoader.applicationContext, phone, ownPhone);
                message = describe(new JSONObject(data));
            } catch (Throwable error) {
                FileLog.e(error);
                message = LocaleController.getString(R.string.GhostPhoneInfoUnavailable);
            }
            final String text = message;
            AndroidUtilities.runOnUIThread(() -> {
                if (dialog.isShowing() && fragment.getParentActivity() != null) dialog.setMessage(text);
            });
        });
    }

    public static String describe(JSONObject data) {
        if (data.has("error")) return LocaleController.getString(R.string.GhostPhoneInvalid);
        if (data.optBoolean("anonymous")) return data.optString("number") + "\n\n" + LocaleController.getString(R.string.GhostPhoneAnonymous);
        StringBuilder text = new StringBuilder(data.optString("formatted", data.optString("number")));
        line(text, R.string.GhostPhoneCountry, data.optString("country"));
        line(text, R.string.GhostPhoneRegion, data.optString("region"));
        line(text, R.string.GhostPhoneLocality, data.optString("locality"));
        line(text, R.string.GhostPhoneOperator, data.optString("operator"));
        line(text, R.string.GhostPhoneRange, data.optString("range"));
        JSONArray zones = data.optJSONArray("timezones");
        if (zones != null && zones.length() > 0 && zones.length() <= 3) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < zones.length(); i++) {
                if (i > 0) list.append(", ");
                list.append(zones.optString(i));
            }
            line(text, R.string.GhostPhoneTimezones, list.toString());
        }
        if (data.optBoolean("range_missing")) text.append("\n\n").append(LocaleController.getString(R.string.GhostPhoneRangeMissing));
        else if (!data.optBoolean("valid")) text.append("\n\n").append(LocaleController.getString(R.string.GhostPhoneUnconfirmed));
        line(text, R.string.GhostPhoneDatabase, data.optString("source") + (data.has("date") ? " · " + data.optString("date") : ""));
        text.append("\n\n").append(LocaleController.getString(R.string.GhostPhoneAllocationNotice));
        return text.toString();
    }

    private static void line(StringBuilder text, int label, String value) {
        if (value != null && !value.isEmpty()) text.append("\n\n").append(LocaleController.getString(label)).append(": ").append(value);
    }
}
