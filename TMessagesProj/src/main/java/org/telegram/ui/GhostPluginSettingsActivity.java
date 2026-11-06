package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PluginBridge;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class GhostPluginSettingsActivity extends BaseFragment {
    private final String pluginId;
    private final String pluginName;
    private final int parentSettingIndex;
    private String settingsPath="[]";
    private String initialSettings;
    private final ArrayList<Setting> settings = new ArrayList<>();
    private Adapter adapter;
    private RecyclerListView list;

    public GhostPluginSettingsActivity(String pluginId, String pluginName) {
        this(pluginId, pluginName, -1);
    }

    private GhostPluginSettingsActivity(String pluginId, String pluginName, int parentSettingIndex) {
        this.pluginId = pluginId;
        this.pluginName = pluginName;
        this.parentSettingIndex = parentSettingIndex;
    }
    private GhostPluginSettingsActivity(String pluginId,String pluginName,String path,String json) {
        this(pluginId,pluginName,-1);settingsPath=path;initialSettings=json;
    }

    @Override
    public boolean onFragmentCreate() {
        if(initialSettings!=null){applySettings(initialSettings);initialSettings=null;}else loadSettings();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(pluginName);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout frame = new FrameLayout(context);
        fragmentView = frame;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter = new Adapter(context));
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        list.setOnItemClickListener((view, position, x, y) -> onSettingClick(settings.get(position),view));
        return frame;
    }

    private void loadSettings() {
        Utilities.pluginsQueue.postRunnable(()->{
            String json=PluginBridge.getSettingsPageJson(pluginId,settingsPath);
            AndroidUtilities.runOnUIThread(()->{applySettings(json);if(adapter!=null)adapter.notifyDataSetChanged();});
        });
    }
    private void applySettings(String json) {
        settings.clear();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Setting setting = new Setting();
                setting.type = object.optString("type");
                setting.key = object.optString("key");
                setting.text = object.optString("text");
                setting.subtext = object.optString("subtext");
                setting.hint = object.optString("hint");
                setting.index = object.optInt("index", i);
                setting.value = object.opt("value");
                if(setting.value==JSONObject.NULL)setting.value=null;
                setting.accent=object.optBoolean("accent");setting.red=object.optBoolean("red");
                setting.clickable = object.optBoolean("clickable");
                setting.hasSubmenu = object.optBoolean("has_submenu");
                setting.nativeView = object.optBoolean("native_view");
                setting.multiline = object.optBoolean("multiline", "edittext".equals(setting.type));
                setting.maxLength = object.optInt("max_length", 0);
                JSONArray items = object.optJSONArray("items");
                if (items != null) for (int j = 0; j < items.length(); j++) setting.items.add(items.optString(j));
                settings.add(setting);
            }
        } catch (Exception ignored) {
        }
    }

    private void onSettingClick(Setting setting,View view) {
        if ("customreaction".equals(setting.type)) {
            String[] reactions = {"Follow global reaction", "❤️", "👍", "👎", "😂", "🔥", "🎉", "😢", "😮", "👀"};
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(setting.text)
                    .setItems(reactions, (dialog, which) -> {
                        String reaction = which == 0 ? "__follow_global__" : reactions[which];
                        if (PluginBridge.setCustomReaction(pluginId, setting.index, reaction)) {
                            setting.value = reaction;
                            setting.subtext = which == 0 ? "Follows global reaction" : reaction;
                            adapter.notifyItemChanged(settings.indexOf(setting));
                        }
                    }).create());
        } else if ("switch".equals(setting.type) && !setting.key.isEmpty()) {
            boolean value = !(setting.value instanceof Boolean && (Boolean) setting.value);
            update(setting, Boolean.toString(value), value);
        } else if ("selector".equals(setting.type) && !setting.items.isEmpty()) {
            CharSequence[] items = setting.items.toArray(new CharSequence[0]);
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(setting.text)
                    .setItems(items, (dialog, which) -> update(setting, Integer.toString(which), which))
                    .create());
        } else if (("input".equals(setting.type) || "edittext".equals(setting.type)) && !setting.key.isEmpty()) {
            EditText input = new EditText(getParentActivity());
            input.setText(setting.value == null ? "" : String.valueOf(setting.value));
            input.setHint(setting.hint);
            input.setSingleLine(!setting.multiline);
            input.setInputType(InputType.TYPE_CLASS_TEXT |
                    (setting.multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
            if (setting.maxLength > 0) {
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(setting.maxLength)});
            }
            int margin = AndroidUtilities.dp(24);
            FrameLayout container = new FrameLayout(getParentActivity());
            container.setPadding(margin, 0, margin, 0);
            container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(setting.text)
                    .setView(container)
                    .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) ->
                            update(setting, JSONObject.quote(input.getText().toString()), input.getText().toString()))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
        } else if (setting.hasSubmenu) {
            try {
                JSONArray path=new JSONArray(settingsPath);path.put(setting.index);String childPath=path.toString();
                Utilities.pluginsQueue.postRunnable(()->{
                    String json=PluginBridge.getSettingsPageJson(pluginId,childPath);
                    AndroidUtilities.runOnUIThread(()->{try{if(new JSONArray(json).length()>0)presentFragment(new GhostPluginSettingsActivity(pluginId,setting.text,childPath,json));}catch(Exception ignored){}});
                });
            } catch(Exception ignored) { }
        } else if (setting.clickable) {
            PluginBridge.invokeSettingPage(pluginId,settingsPath,setting.index,view);
        }
    }

    private void update(Setting setting, String json, Object value) {
        Utilities.pluginsQueue.postRunnable(()->{
            if(PluginBridge.setSettingPageJson(pluginId,settingsPath,setting.key,json))loadSettings();
        });
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return new ArrayList<>();
    }

    private static class Setting {
        String type, key, text, subtext, hint;
        int index;
        Object value;
        boolean clickable, hasSubmenu, multiline, nativeView;
        boolean accent,red;
        int maxLength;
        final ArrayList<String> items = new ArrayList<>();
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        Adapter(Context context) { this.context = context; }
        @Override public int getItemCount() { return settings.size(); }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) {
            String type = settings.get(holder.getAdapterPosition()).type;
            return "switch".equals(type) || "selector".equals(type) || "input".equals(type) ||
                    "edittext".equals(type) || "customreaction".equals(type) ||
                    settings.get(holder.getAdapterPosition()).clickable;
        }
        @Override public int getItemViewType(int position) {
            String type = settings.get(position).type;
            if ("header".equals(type)) return 0;
            if ("switch".equals(type)) return 1;
            if ("divider".equals(type)) return 3;
            if (settings.get(position).nativeView) return 4;
            return 2;
        }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view;
            if (type == 0) view = new HeaderCell(context, getResourceProvider());
            else if (type == 1) view = new TextCheckCell(context, getResourceProvider());
            else if (type == 3) view = new TextInfoPrivacyCell(context, getResourceProvider());
            else if (type == 4) view = new FrameLayout(context);
            else view = new TextSettingsCell(context, getResourceProvider());
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Setting setting = settings.get(position);
            int type = holder.getItemViewType();
            if (type == 4) {
                FrameLayout container = (FrameLayout) holder.itemView;
                container.removeAllViews();
                View custom = PluginBridge.customSettingView(pluginId, parentSettingIndex, setting.index, context, list, currentAccount, classGuid, getResourceProvider(), this);
                if (custom != null) {
                    if (custom.getParent() instanceof ViewGroup) ((ViewGroup) custom.getParent()).removeView(custom);
                    container.addView(custom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
            } else if (type == 0) {
                ((HeaderCell) holder.itemView).setText(setting.text);
            } else if (type == 1) {
                boolean checked = setting.value instanceof Boolean && (Boolean) setting.value;
                ((TextCheckCell) holder.itemView).setTextAndValueAndCheck(setting.text, setting.subtext, checked, true, true);
            } else if (type == 3) {
                ((TextInfoPrivacyCell) holder.itemView).setText(setting.text);
            } else {
                String value = setting.subtext;
                if ("selector".equals(setting.type) && setting.value instanceof Number) {
                    int selected = ((Number) setting.value).intValue();
                    if (selected >= 0 && selected < setting.items.size()) value = setting.items.get(selected);
                } else if (("input".equals(setting.type) || "edittext".equals(setting.type)) && setting.value != null) {
                    value = String.valueOf(setting.value);
                }
                ((TextSettingsCell) holder.itemView).setTextAndValue(setting.text, value, true);
                ((TextSettingsCell) holder.itemView).setTextColor(getThemedColor(setting.red?Theme.key_text_RedRegular:setting.accent?Theme.key_windowBackgroundWhiteBlueText:Theme.key_windowBackgroundWhiteBlackText));
            }
        }
    }
}
