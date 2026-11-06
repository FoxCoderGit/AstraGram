package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PluginBridge;
import org.telegram.messenger.GhostOptionalFeatures;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
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

public class GhostPluginsActivity extends BaseFragment {
    private static final int REQUEST_PLUGIN = 9107;
    private static final String[] BUILTINS={GhostOptionalFeatures.MUSIC,GhostOptionalFeatures.DRAWING,GhostOptionalFeatures.VIDEO,GhostOptionalFeatures.EXTERA_SUPPORTERS};
    private static final String[] BUILTIN_NAMES={"Я.Музыка","Рисовка","yt-dlp","Поддержавшие exteraGram"};
    private static final String[] BUILTIN_DESCRIPTIONS={"Поиск, понравившиеся треки и отправка музыки","Рисование голосовых сообщений","Загрузка и отправка видео по ссылкам из YouTube, TikTok, Instagram и других сервисов","Отметки поддержки exteraGram из его публичного API"};

    private static final int MANAGEMENT_HEADER=BUILTINS.length+1, ENGINE_ROW=MANAGEMENT_HEADER+1, INSTALL_ROW=ENGINE_ROW+1, RELOAD_ROW=INSTALL_ROW+1, INSTALLED_HEADER=RELOAD_ROW+1, INSTALLED_START=INSTALLED_HEADER+1;

    private final ArrayList<PluginInfo> plugins = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean engineEnabled,safeMode;
    private String query="";
    private final ArrayList<PluginInfo> allPlugins=new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        Utilities.pluginsQueue.postRunnable(()->{PluginBridge.initialize();AndroidUtilities.runOnUIThread(this::refreshPlugins);});
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.GhostPlugins));
        actionBar.createMenu().addItem(10,R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener(){
            @Override public void onTextChanged(android.widget.EditText input){query=input.getText().toString().toLowerCase(java.util.Locale.ROOT);filterPlugins();}
        });
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frame = new FrameLayout(context);
        fragmentView = frame;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position, x, y) -> {
            if(position>=1&&position<=BUILTINS.length){
                String feature=BUILTINS[position-1];
                GhostOptionalFeatures.setEnabled(feature,!GhostOptionalFeatures.enabled(feature));
                adapter.notifyItemChanged(position);
            } else if(position==ENGINE_ROW){
                Utilities.pluginsQueue.postRunnable(()->{PluginBridge.setEngineEnabled(!engineEnabled);AndroidUtilities.runOnUIThread(this::refreshPlugins);});
            } else if(position==INSTALL_ROW){
                if(!engineEnabled){showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("Движок плагинов выключен").setMessage("Включите движок внешних плагинов перед установкой файла.").setPositiveButton("OK",null).create());return;}
                selectPluginFile();
            } else if (position == RELOAD_ROW) {
                Utilities.pluginsQueue.postRunnable(() -> {
                    if(safeMode)PluginBridge.clearSafeMode();else PluginBridge.reloadAll();
                    AndroidUtilities.runOnUIThread(this::refreshPlugins);
                });
            } else if (position >= INSTALLED_START && position < INSTALLED_START + plugins.size()) {
                PluginInfo plugin = plugins.get(position - INSTALLED_START);
                Utilities.pluginsQueue.postRunnable(() -> {
                    PluginBridge.setEnabled(plugin.id, !plugin.enabled);
                    AndroidUtilities.runOnUIThread(() -> {
                        refreshPlugins();
                    });
                });
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= INSTALLED_START && position < INSTALLED_START + plugins.size()) {
                PluginInfo plugin = plugins.get(position - INSTALLED_START);
                showPluginDetails(plugin);
                return true;
            }
            return false;
        });
        return fragmentView;
    }

    private void selectPluginFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PLUGIN);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PLUGIN || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        Utilities.pluginsQueue.postRunnable(() -> {
            String path=PluginBridge.preparePluginImport(uri);
            if(path!=null)PluginBridge.showPluginInstallDialog(this,path,true,this::refreshPlugins);
            else AndroidUtilities.runOnUIThread(()->{if(getParentActivity()!=null)showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("Ошибка плагина").setMessage(PluginBridge.getLastInstallError()).setPositiveButton("OK",null).create());});
        });
    }

    private void refreshPlugins() {
        Utilities.pluginsQueue.postRunnable(()->{
            String json=PluginBridge.getPluginsJson(),state=PluginBridge.getEngineStateJson();
            AndroidUtilities.runOnUIThread(()->{loadPlugins(json,state);filterPlugins();});
        });
    }

    private void filterPlugins(){plugins.clear();for(PluginInfo plugin:allPlugins)if(plugin.name.toLowerCase(java.util.Locale.ROOT).contains(query))plugins.add(plugin);if(adapter!=null)adapter.notifyDataSetChanged();}

    private void loadPlugins(String json,String state) {
        allPlugins.clear();
        try {
            JSONObject engine=new JSONObject(state);engineEnabled=engine.optBoolean("enabled");safeMode=engine.optBoolean("safe_mode");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                PluginInfo plugin = new PluginInfo();
                plugin.id = object.optString("id");
                plugin.name = object.optString("name", plugin.id);
                plugin.version = object.isNull("version")?"":object.optString("version");
                plugin.author = object.isNull("author")?"":object.optString("author");
                plugin.description = object.isNull("description")?"":object.optString("description");
                plugin.error = object.optString("error");
                plugin.enabled = object.optBoolean("enabled");
                plugin.hasSettings = object.optBoolean("has_settings");
                plugin.pinned=object.optBoolean("pinned");
                allPlugins.add(plugin);
            }
            allPlugins.sort((left,right)->left.pinned!=right.pinned?(left.pinned?-1:1):left.name.compareToIgnoreCase(right.name));
        } catch (Exception ignored) {
        }
    }

    private void showPluginDetails(PluginInfo plugin) {
        if (getParentActivity() == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        if (!plugin.version.isEmpty()) {
            message.append(LocaleController.getString(R.string.GhostPluginVersion)).append(": ").append(plugin.version);
        }
        if (!plugin.author.isEmpty()) {
            if (message.length() > 0) message.append("\n");
            message.append(LocaleController.getString(R.string.GhostPluginAuthor)).append(": ").append(plugin.author);
        }
        if (!plugin.description.isEmpty()) {
            if (message.length() > 0) message.append("\n\n");
            message.append(plugin.description);
        }
        if (!plugin.error.isEmpty()) {
            if (message.length() > 0) message.append("\n\n");
            message.append(LocaleController.getString(R.string.GhostPluginError)).append(":\n").append(plugin.error);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(plugin.name)
                .setMessage(message.length() == 0 ? plugin.id : message.toString())
                .setItems(new CharSequence[]{plugin.pinned?"Открепить":"Закрепить","Экспортировать .plugin","Открыть в…"},(dialog,which)->{
                    if(which==0){PluginBridge.setPinned(plugin.id,!plugin.pinned);refreshPlugins();}
                    else PluginBridge.exportPlugin(getParentActivity(),plugin.id,which==2);
                })
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .setNegativeButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                    Utilities.pluginsQueue.postRunnable(() -> {
                        PluginBridge.remove(plugin.id);
                        AndroidUtilities.runOnUIThread(this::refreshPlugins);
                    });
                });
        if (plugin.hasSettings) {
            builder.setNeutralButton(LocaleController.getString(R.string.Settings), (dialog, which) ->
                    presentFragment(new GhostPluginSettingsActivity(plugin.id, plugin.name)));
        }
        showDialog(builder.create());
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return new ArrayList<>();
    }

    private static final class PluginInfo {
        String id;
        String name;
        String version;
        String author;
        String description;
        String error;
        boolean enabled;
        boolean hasSettings;
        boolean pinned;
    }

    private final class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position>=1&&position<=BUILTINS.length || position>=ENGINE_ROW&&position<=RELOAD_ROW || position >= INSTALLED_START && position < INSTALLED_START + plugins.size();
        }

        @Override
        public int getItemCount() {
            return INSTALLED_START+1 + plugins.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 || position == MANAGEMENT_HEADER || position == INSTALLED_HEADER) return 0;
            if (position == INSTALL_ROW || position == RELOAD_ROW) return 1;
            if(position==ENGINE_ROW)return 2;
            if(position>=1&&position<=BUILTINS.length)return 2;
            if (position >= INSTALLED_START && position < INSTALLED_START + plugins.size()) return 2;
            return 3;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout row = new FrameLayout(context);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            FrameLayout outer=(FrameLayout)holder.itemView;
            outer.removeAllViews();outer.setPadding(AndroidUtilities.dp(12),0,AndroidUtilities.dp(12),0);
            View content;
            if (type == 0) {
                android.widget.LinearLayout block=new android.widget.LinearLayout(context);block.setOrientation(android.widget.LinearLayout.VERTICAL);
                if(position==0){
                    View hero=org.telegram.ui.Components.GhostSettingsCards.row(context,"Плагины","Расширяйте возможности AstraGram с помощью плагинов.",null,null,R.drawable.msg_customize,null);
                    hero.setPadding(AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12));hero.setMinimumHeight(AndroidUtilities.dp(68));
                    android.widget.ImageView heroIcon=(android.widget.ImageView)((android.widget.LinearLayout)hero).getChildAt(0);heroIcon.getLayoutParams().width=heroIcon.getLayoutParams().height=AndroidUtilities.dp(44);heroIcon.setPadding(AndroidUtilities.dp(10),AndroidUtilities.dp(10),AndroidUtilities.dp(10),AndroidUtilities.dp(10));heroIcon.setBackground(org.telegram.ui.Components.GhostSettingsCards.background(Theme.getColor(Theme.key_featuredStickers_addButton),14));heroIcon.setColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText));block.addView(hero);
                }
                HeaderCell header=new HeaderCell(context,getResourceProvider());header.setHeight(36);header.setTextSize(13);header.setText(position==0?"Встроенные возможности":position==MANAGEMENT_HEADER?"Управление плагинами":"Установленные плагины");block.addView(header);content=block;
            } else if (type == 1) {
                content=org.telegram.ui.Components.GhostSettingsCards.row(context,position==INSTALL_ROW?"Установить файл .plugin":"Перезагрузить плагины",null,"›",null,position==INSTALL_ROW?R.drawable.msg_download:R.drawable.msg_retry,null);
            } else if (type == 2) {
                if(position>=1&&position<=BUILTINS.length){
                    int[] icons={R.drawable.bot_music,R.drawable.msg_edit,R.drawable.msg_download,R.drawable.outline_shield_check};
                    content=org.telegram.ui.Components.GhostSettingsCards.row(context,BUILTIN_NAMES[position-1],BUILTIN_DESCRIPTIONS[position-1],null,GhostOptionalFeatures.enabled(BUILTINS[position-1]),icons[position-1],null);
                } else if(position==ENGINE_ROW){content=org.telegram.ui.Components.GhostSettingsCards.row(context,"Движок внешних плагинов",safeMode?"Безопасный режим после сбоя":"Python-плагины и Java-хуки",null,engineEnabled,R.drawable.msg_customize,null);
                }else{
                PluginInfo plugin = plugins.get(position - INSTALLED_START);
                String value = plugin.version;
                if (!plugin.error.isEmpty()) {
                    value = LocaleController.getString(R.string.GhostPluginHasError);
                } else if (!plugin.author.isEmpty()) {
                    value = value.isEmpty() ? plugin.author : value + " · " + plugin.author;
                }
                content=org.telegram.ui.Components.GhostSettingsCards.row(context,plugin.name,value,null,plugin.enabled,R.drawable.msg_customize,null);
                }
            } else {
                TextInfoPrivacyCell footer=new TextInfoPrivacyCell(context,getResourceProvider());footer.setText(plugins.isEmpty()?"Нет установленных плагинов. Удерживайте установленный плагин, чтобы открыть его настройки и действия.":LocaleController.getString(R.string.GhostPluginsInfo));content=footer;
            }
            if(type==1||type==2){
                android.widget.LinearLayout row=(android.widget.LinearLayout)content;
                row.setMinimumHeight(AndroidUtilities.dp(52));row.setPadding(AndroidUtilities.dp(12),AndroidUtilities.dp(9),AndroidUtilities.dp(12),AndroidUtilities.dp(9));
                android.widget.ImageView icon=(android.widget.ImageView)row.getChildAt(0);icon.getLayoutParams().width=icon.getLayoutParams().height=AndroidUtilities.dp(32);icon.setPadding(AndroidUtilities.dp(6),AndroidUtilities.dp(6),AndroidUtilities.dp(6),AndroidUtilities.dp(6));
                icon.setBackground(org.telegram.ui.Components.GhostSettingsCards.background(androidx.core.graphics.ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText),35),12));
                android.widget.LinearLayout labels=(android.widget.LinearLayout)row.getChildAt(1);
                for(int i=0;i<labels.getChildCount();i++){android.widget.TextView label=(android.widget.TextView)labels.getChildAt(i);label.setTextSize(i==0?15:12);label.setMaxLines(i==0?1:2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);if(i==0)label.setTypeface(android.graphics.Typeface.DEFAULT);else ((android.widget.LinearLayout.LayoutParams)label.getLayoutParams()).topMargin=AndroidUtilities.dp(2);}
                boolean first=position==1||position==ENGINE_ROW||position==INSTALLED_START;
                boolean last=position==BUILTINS.length||position==RELOAD_ROW||position==INSTALLED_START+plugins.size()-1;
                android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));float radius=AndroidUtilities.dp(18);shape.setCornerRadii(new float[]{first?radius:0,first?radius:0,first?radius:0,first?radius:0,last?radius:0,last?radius:0,last?radius:0,last?radius:0});content.setBackground(shape);
                if(!last){View divider=new View(context);divider.setBackgroundColor(Theme.getColor(Theme.key_divider));FrameLayout.LayoutParams dp=new FrameLayout.LayoutParams(-1,1,android.view.Gravity.BOTTOM);dp.leftMargin=AndroidUtilities.dp(68);dp.rightMargin=AndroidUtilities.dp(12);outer.addView(content,new FrameLayout.LayoutParams(-1,-2));outer.addView(divider,dp);return;}
            }
            outer.addView(content,new FrameLayout.LayoutParams(-1,-2));
        }
    }
}
