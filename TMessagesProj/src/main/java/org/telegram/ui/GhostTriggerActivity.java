package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
import androidx.recyclerview.widget.*;

/** Launcher-only package visibility; no QUERY_ALL_PACKAGES permission. */
public final class GhostTriggerActivity extends UniversalFragment {
    @Override protected String getTitle(){return "Триггер";}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        items.add(UItem.asHeader("Главное меню"));
        items.add(UItem.asCheck(1,"Показывать триггер").setChecked(GhostAppTrigger.enabled()));
        items.add(UItem.asShadow("Кнопка «Триггер» запускает выбранное приложение из главного меню AstraGram."));
        items.add(UItem.asHeader("Приложение"));
        String selected=GhostAppTrigger.selected(),label="Не выбрано";
        if(!selected.isEmpty())try{label=getParentActivity().getPackageManager().getApplicationLabel(getParentActivity().getPackageManager().getApplicationInfo(selected,0)).toString();}catch(Exception e){label="Приложение недоступно";}
        items.add(UItem.asButton(2,R.drawable.msg_customize,"Выбрать приложение"));
        android.graphics.drawable.Drawable selectedIcon=null;
        if(!selected.isEmpty())try{selectedIcon=getParentActivity().getPackageManager().getApplicationIcon(selected);}catch(Exception ignored){}
        if(selectedIcon!=null){android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(AndroidUtilities.dp(28),AndroidUtilities.dp(28),android.graphics.Bitmap.Config.ARGB_8888);bitmap.setDensity(ApplicationLoader.applicationContext.getResources().getDisplayMetrics().densityDpi);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);selectedIcon.setBounds(0,0,bitmap.getWidth(),bitmap.getHeight());selectedIcon.draw(canvas);selectedIcon=new android.graphics.drawable.BitmapDrawable(ApplicationLoader.applicationContext.getResources(),bitmap){@Override public void setColorFilter(android.graphics.ColorFilter filter){}};}
        UItem current=selectedIcon==null?UItem.asButton(3,R.drawable.ghost_trigger,"Выбранное приложение"):UItem.asButton(3,selectedIcon,"Выбранное приложение");
        current.textValue=label;
        items.add(current);
        items.add(UItem.asButton(4,R.drawable.msg_delete,"Сбросить выбор").setEnabled(!selected.isEmpty()));
        items.add(UItem.asShadow("Выбранное приложение запускается из главного меню AstraGram. Настройка общая для аккаунтов на этом устройстве."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        if(item.id==1){GhostAppTrigger.setEnabled(!GhostAppTrigger.enabled());listView.adapter.update(true);}
        else if(item.id==2||item.id==3)presentFragment(new AppPicker(()->listView.adapter.update(true)));
        else if(item.id==4){GhostAppTrigger.select("");listView.adapter.update(true);}
    }
    @Override public void onResume(){super.onResume();if(listView!=null)listView.adapter.update(false);}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}

    private static final class AppPicker extends BaseFragment{
        final Runnable changed;
        final ArrayList<ResolveInfo> all=new ArrayList<>(),visible=new ArrayList<>();
        RecyclerListView list;
        RecyclerListView.SelectionAdapter adapter;
        String query="";
        boolean destroyed;
        AppPicker(Runnable changed){this.changed=changed;}
        void filter(){visible.clear();for(ResolveInfo app:all)if(app.loadLabel(getParentActivity().getPackageManager()).toString().toLowerCase(Locale.ROOT).contains(query)||app.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(query))visible.add(app);adapter.notifyDataSetChanged();}
        @Override public View createView(Context context){
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle("Выбрать приложение");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
            actionBar.createMenu().addItem(1,R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener(){@Override public void onTextChanged(EditText input){query=input.getText().toString().toLowerCase(Locale.ROOT);filter();}});
            FrameLayout frame=new FrameLayout(context);fragmentView=frame;frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            list=new RecyclerListView(context);list.setLayoutManager(new LinearLayoutManager(context));
            adapter=new RecyclerListView.SelectionAdapter(){
                @Override public boolean isEnabled(RecyclerView.ViewHolder holder){return true;}
                @Override public int getItemCount(){return visible.size();}
                @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
                    LinearLayout row=new LinearLayout(context);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(AndroidUtilities.dp(18),AndroidUtilities.dp(10),AndroidUtilities.dp(18),AndroidUtilities.dp(10));
                    row.addView(new ImageView(context),new LinearLayout.LayoutParams(AndroidUtilities.dp(42),AndroidUtilities.dp(42)));
                    TextView title=GhostSettingsCards.text(context,"",16,Theme.key_windowBackgroundWhiteBlackText);title.setMaxLines(2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMarginStart(AndroidUtilities.dp(18));row.addView(title,p);
                    return new RecyclerListView.Holder(row);
                }
                @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){ResolveInfo app=visible.get(position);LinearLayout row=(LinearLayout)holder.itemView;((ImageView)row.getChildAt(0)).setImageDrawable(app.loadIcon(context.getPackageManager()));((TextView)row.getChildAt(1)).setText(app.loadLabel(context.getPackageManager()));}
            };
            list.setAdapter(adapter);frame.addView(list,LayoutHelper.createFrame(-1,-1));
            list.setOnItemClickListener((v,p)->{if(p<0||p>=visible.size())return;GhostAppTrigger.select(visible.get(p).activityInfo.packageName);changed.run();finishFragment();});
            Utilities.globalQueue.postRunnable(()->{
                Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                ArrayList<ResolveInfo> apps=new ArrayList<>(context.getPackageManager().queryIntentActivities(launcher,0));
                apps.sort((a,b)->a.loadLabel(context.getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(context.getPackageManager()).toString()));
                java.util.HashSet<String> seen=new java.util.HashSet<>();apps.removeIf(app->!app.activityInfo.exported||!app.activityInfo.enabled||!seen.add(app.activityInfo.packageName));
                AndroidUtilities.runOnUIThread(()->{if(!destroyed){all.addAll(apps);filter();}});
            });
            return frame;
        }
        @Override public void onFragmentDestroy(){destroyed=true;super.onFragmentDestroy();}
    }
}
