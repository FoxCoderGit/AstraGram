package org.telegram.ui;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.*;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Consumer;

/** Photos and a small, account-specific settings sheet. Never follows selectedAccount. */
public final class GhostCloudActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private GhostCloud cloud;
    private TextView state,summary;
    private ProgressBar progress;
    private LinearLayout uploadPanel;
    private GhostCloudGallery gallery;
    private boolean statsLoading;
    private boolean resumed;
    private boolean restored;
    private long noticeUntil;
    private String notice;
    private final java.util.HashMap<String,GhostCloudStore.Photo> downloads=new java.util.HashMap<>();
    private final Runnable refresh=new Runnable(){@Override public void run(){if(resumed){update();AndroidUtilities.runOnUIThread(this,2000);}}};
    public GhostCloudActivity(int account){currentAccount=account;}
    @Override public boolean onFragmentCreate() {
        cloud=GhostCloud.get(currentAccount);
        getNotificationCenter().addObserver(this,NotificationCenter.fileLoaded);
        getNotificationCenter().addObserver(this,NotificationCenter.fileLoadFailed);
        return super.onFragmentCreate();
    }
    @Override public View createView(Context context) {
        actionBar.setTitle("Облако");actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.createMenu().addItem(4,R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener(){
            @Override public void onTextChanged(android.widget.EditText field){if(gallery!=null)gallery.search(field.getText().toString());}
            @Override public void onSearchCollapse(){if(gallery!=null)gallery.search("");}
        }).setSearchFieldHint("Поиск по имени файла");
        actionBar.createMenu().addItem(5,R.drawable.menu_tag_filter);
        ActionBarMenuItem menu=actionBar.createMenu().addItem(0,R.drawable.ic_ab_other);
        menu.addSubItem(1,R.drawable.msg_settings,"Настройки");
        menu.addSubItem(2,R.drawable.msg_saved,"Восстановить список облака");
        menu.addSubItem(3,R.drawable.msg_info,"Ошибки чтения фотографий");
        menu.addSubItem(10,R.drawable.msg_calendar,"Перейти к дате");
        menu.addSubItem(11,R.drawable.menu_tag_filter,"Период просмотра");
        menu.addSubItem(12,R.drawable.msg_gallery,"Размер миниатюр");
        menu.addSubItem(13,R.drawable.msg_customize,"Сортировка по дате");
        menu.addSubItem(14,R.drawable.ghost_menu_tasks,"Показать / скрыть панель загрузки");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();else if(id==1)settings();else if(id==2&&cloud!=null)cloud.restore(()->update());else if(id==3&&cloud!=null){String errors=cloud.store.sourceErrors();showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle("Ошибки чтения").setMessage(errors.isEmpty()?"Нет ошибок":errors).setPositiveButton(LocaleController.getString(R.string.OK),null).create());}else if(gallery!=null){if(id==5)gallery.filters();else if(id==10)gallery.jump();else if(id==11)gallery.period();else if(id==12)gallery.density();else if(id==13)gallery.sorting();else if(id==14){boolean visible=uploadPanel.getVisibility()!=View.VISIBLE;uploadPanel.setVisibility(visible?View.VISIBLE:View.GONE);cloud.store.preferences.edit().putBoolean("gallery_upload_panel",visible).apply();}}}});
        LinearLayout root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        uploadPanel=new LinearLayout(context);uploadPanel.setOrientation(LinearLayout.VERTICAL);root.addView(uploadPanel);
        uploadPanel.setVisibility(cloud==null||cloud.store.preferences.getBoolean("gallery_upload_panel",true)?View.VISIBLE:View.GONE);
        state=label(context,"",16,Theme.key_windowBackgroundWhiteBlackText);state.setPadding(dp(20),dp(16),dp(20),dp(4));uploadPanel.addView(state);
        summary=label(context,"",13,Theme.key_windowBackgroundWhiteGrayText);summary.setPadding(dp(20),0,dp(20),dp(10));uploadPanel.addView(summary);
        progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);uploadPanel.addView(progress,LayoutHelper.createLinear(-1,3,20,0,20,0));
        LinearLayout controls=new LinearLayout(context);
        control(controls,"Пауза",()->{if(cloud!=null)cloud.pause(true);update();});
        control(controls,"Продолжить",()->{if(cloud==null)return;if(!cloud.store.config().enabled)settings();else cloud.pause(false);update();});
        control(controls,"Повторить ошибки",()->{if(cloud!=null){cloud.store.retryErrors();GhostCloudJobs.schedule(currentAccount,0);}update();});
        uploadPanel.addView(controls,new LinearLayout.LayoutParams(-1,dp(48)));
        if(cloud!=null){gallery=new GhostCloudGallery(context,this,cloud,this::photo);root.addView(gallery.view,new LinearLayout.LayoutParams(-1,0,1));}
        fragmentView=root;update();return root;
    }
    private int dp(float n){return AndroidUtilities.dp(n);}
    private TextView label(Context context,String text,int size,int key){TextView v=new TextView(context);v.setText(text);v.setTextSize(size);v.setTextColor(getThemedColor(key));return v;}
    private void control(LinearLayout parent,String title,Runnable action){TextView v=label(parent.getContext(),title,13,Theme.key_windowBackgroundWhiteBlueText);v.setGravity(Gravity.CENTER);v.setBackground(Theme.getSelectorDrawable(false));v.setOnClickListener(view->action.run());parent.addView(v,new LinearLayout.LayoutParams(0,-1,1));}
    private void update() {
        if(state==null)return;
        if(cloud==null){state.setText("Облако доступно для обычного аккаунта Telegram");return;}
        String wait=cloud.waitReason();state.setText(cloud.isRestoring()||!cloud.store.config().enabled&&restored?cloud.status:wait==null?cloud.status:wait);
        if(!statsLoading){statsLoading=true;Utilities.globalQueue.postRunnable(()->{try{long[] counts=cloud.store.statistics();long errors=cloud.store.sourceErrorCount();AndroidUtilities.runOnUIThread(()->{statsLoading=false;if(summary!=null)summary.setText("Сохранено: "+counts[0]+" · "+AndroidUtilities.formatFileSize(counts[3])+"\nВ очереди: "+counts[1]+" · Ошибки: "+(counts[2]+errors));});}catch(Exception e){statsLoading=false;FileLog.e(e);}});}
        progress.setProgress((int)(cloud.progress*1000));
        if(!downloads.isEmpty()){
            java.util.Map.Entry<String,GhostCloudStore.Photo> transfer=downloads.entrySet().iterator().next();
            long[] bytes=ImageLoader.getInstance().getFileProgressSizes(transfer.getKey());
            state.setText("Скачиваем оригинал · "+transfer.getValue().name);
            progress.setProgress(bytes==null||bytes[1]==0?0:(int)(1000*bytes[0]/bytes[1]));
        }else if(notice!=null&&System.currentTimeMillis()<noticeUntil)state.setText(notice);
        if(gallery!=null)gallery.refresh();
    }
    private MessageObject message(GhostCloudStore.Photo p){TLRPC.Message m=cloud.store.decode(p.message);return m==null?null:new MessageObject(currentAccount,m,false,false);}
    private void photo(GhostCloudStore.Photo p) {
        MessageObject m=message(p);
        String detail=new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(new Date(p.taken))+"\n"+AndroidUtilities.formatFileSize(p.size);
        if(p.error!=null)detail+="\n"+p.error;
        String[] items=m==null?new String[]{"Повторить загрузку"}:new String[]{"Просмотреть","Скачать оригинал","Показать в канале","Удалить из облака"};
        showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(p.name).setMessage(detail).setItems(items,(dialog,which)->{
            if(m==null){cloud.store.retryErrors();GhostCloudJobs.schedule(currentAccount,0);return;}
            if(which==0)GhostCloudPreview.open(this,cloud,p,m);
            else if(which==1)download(p,m);
            else if(which==2){Bundle args=new Bundle();args.putLong("chat_id",cloud.channelId());args.putInt("message_id",p.id);presentFragment(new ChatActivity(args));}
            else showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle("Удалить из облака?")
                .setMessage("Фотография на телефоне останется. Эта копия не будет загружена повторно автоматически.")
                .setPositiveButton(LocaleController.getString(R.string.Delete),(d,w)->{cloud.delete(p.hash);update();}).setNegativeButton(LocaleController.getString(R.string.Cancel),null).create());
        }).create());
    }
    private void download(GhostCloudStore.Photo p,MessageObject message) {
        Utilities.globalQueue.postRunnable(()->{
            final boolean original=GhostCloudMedia.findOriginal(cloud.store,p)!=null;
            AndroidUtilities.runOnUIThread(()->{if(getParentActivity()==null)return;if(original){notice="Оригинал уже есть на телефоне";noticeUntil=System.currentTimeMillis()+10000;update();}else downloadRemote(p,message);});
        });
    }
    private void downloadRemote(GhostCloudStore.Photo p,MessageObject message) {
        TLRPC.Document doc=message.getDocument();if(doc==null)return;
        File file=getFileLoader().getPathToAttach(doc,true);
        if(file.exists()){saveOriginal(p,file);return;}
        downloads.put(FileLoader.getAttachFileName(doc),p);getFileLoader().loadFile(doc,message,FileLoader.PRIORITY_HIGH,1);
        state.setText("Скачиваем оригинал · "+p.name);
    }
    private void saveOriginal(GhostCloudStore.Photo p,File file) {
        if(Build.VERSION.SDK_INT<29 && getParentActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){getParentActivity().requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},9482);return;}
        GhostCloudExport.save(file,p,(uri,error)->{if(uri!=null&&error==null){android.content.ContentValues values=new android.content.ContentValues();values.put("uri",uri.toString());cloud.store.update(p.hash,values);}if(getParentActivity()!=null){notice=error!=null?error:uri==null?"Не удалось сохранить оригинал":"Оригинал сохранён в галерею";noticeUntil=System.currentTimeMillis()+10000;update();}});
    }
    @Override public void didReceivedNotification(int id,int account,Object... args) {
        if(args.length==0||!(args[0] instanceof String))return;
        String name=(String)args[0];GhostCloudStore.Photo p=downloads.remove(name);if(p==null)return;
        if(id==NotificationCenter.fileLoaded){MessageObject m=message(p);if(m!=null)saveOriginal(p,getFileLoader().getPathToAttach(m.getDocument(),true));}
        else if(state!=null){notice="Не удалось скачать оригинал. Повторите попытку.";noticeUntil=System.currentTimeMillis()+10000;update();}
    }
    @Override public void onResume(){super.onResume();resumed=true;if(cloud!=null&&!restored&&!cloud.store.config().enabled){restored=true;cloud.restore(()->{if(resumed)update();});}refresh.run();}
    @Override public void onPause(){resumed=false;AndroidUtilities.cancelRunOnUIThread(refresh);super.onPause();}
    @Override public void onFragmentDestroy(){if(gallery!=null)gallery.destroy();resumed=false;AndroidUtilities.cancelRunOnUIThread(refresh);getNotificationCenter().removeObserver(this,NotificationCenter.fileLoaded);getNotificationCenter().removeObserver(this,NotificationCenter.fileLoadFailed);super.onFragmentDestroy();}
    @Override public void onRequestPermissionsResultFragment(int code,String[] permissions,int[] results){super.onRequestPermissionsResultFragment(code,permissions,results);if(code==9481){update();if(GhostCloudMedia.permission())settings();}}
    private void permissions(){
        ArrayList<String> list=new ArrayList<>();list.add(Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE);
        if(Build.VERSION.SDK_INT>=33&&cloud!=null&&cloud.store.config().video)list.add(Manifest.permission.READ_MEDIA_VIDEO);
        if(Build.VERSION.SDK_INT>=29)list.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        getParentActivity().requestPermissions(list.toArray(new String[0]),9481);
    }
    private void settings() {
        if(cloud==null)return;
        if(!GhostCloudMedia.permission(cloud.store.config().video)||!GhostCloudMedia.originalPermission()) {
            showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle("Доступ к оригиналам")
                .setMessage("Для автоматической копии выбранных папок нужен доступ ко всем фотографиям. Доступ к метаданным сохраняет EXIF оригинала, включая записанную в фото геопозицию.")
                .setPositiveButton("Разрешить",(d,w)->permissions()).setNegativeButton(LocaleController.getString(R.string.Cancel),null).create());return;
        }
        showDialog(new SettingsSheet(getParentActivity()));
    }
    private final class SettingsSheet extends BottomSheet {
        final GhostCloudStore.Config config=cloud.store.config();
        final TextView folders,period,window;
        final TextCheckCell scheduleCell;
        SettingsSheet(Context context) {
            super(context,true,getResourceProvider());setTitle("Настройки облака",true);
            LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll=new ScrollView(context);scroll.addView(content);setCustomView(scroll);
            TextView account=label(context,"Для этого аккаунта · приватный канал без других участников",13,Theme.key_dialogTextGray);account.setPadding(dp(20),dp(8),dp(20),dp(12));content.addView(account);
            folders=row(content,"Папки",()->chooseFolders());period=row(content,"Период",()->choosePeriod());
            check(content,"Разрешать мобильную сеть",config.mobile,value->config.mobile=value);
            check(content,"Сохранять видео",config.video,value->{config.video=value;if(value&&Build.VERSION.SDK_INT>=33&&!GhostCloudMedia.permission(true))getParentActivity().requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO},9483);});
            check(content,"Только на зарядке",config.charging,value->config.charging=value);
            scheduleCell=check(content,"Расписание",config.scheduled,value->{config.scheduled=value;labels();});
            window=row(content,"Дни и время",()->chooseDays());
            ButtonWithCounterView save=new ButtonWithCounterView(context,getResourceProvider()).setRound();save.setText(config.enabled?"Проверить и применить":"Проверить и включить");
            LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,dp(48));button.setMargins(dp(20),dp(16),dp(20),dp(12));content.addView(save,button);
            save.setOnClickListener(v->{
                if(!GhostCloudMedia.permission(config.video)){new AlertDialog.Builder(context,getResourceProvider()).setTitle("Доступ к медиа").setMessage("Разрешите доступ к фото и видео для копирования выбранных папок.").setPositiveButton("Разрешить",(d,w)->{if(Build.VERSION.SDK_INT>=33)getParentActivity().requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO},9483);else permissions();}).show();return;}
                if(!config.camera&&config.folders.isEmpty()){new AlertDialog.Builder(context,getResourceProvider()).setTitle("Выберите папку").setMessage("Нужно выбрать хотя бы одну папку для фотографий.").setPositiveButton(LocaleController.getString(R.string.OK),null).show();return;}
                if(config.scheduled&&config.days==0)return;
                save.setLoading(true);save.setEnabled(false);
                cloud.preview(config,result->{save.setLoading(false);save.setEnabled(true);if(getParentActivity()==null)return;
                    if(result[0]<0){new AlertDialog.Builder(context,getResourceProvider()).setTitle("Не удалось прочитать галерею").setPositiveButton(LocaleController.getString(R.string.OK),null).show();return;}
                    String text="Фотографий за выбранный период: "+result[0]+"\nОбщий объём: "+AndroidUtilities.formatFileSize(result[1])+"\n\nОригиналы отправляются файлами без сжатия. Уже сохранённые копии будут пропущены.";
                    if(config.period==0)text+="\nБудут загружаться фотографии, добавленные после выбранного начала наблюдения.";
                    new AlertDialog.Builder(context,getResourceProvider()).setTitle("Включить облако?").setMessage(text)
                        .setPositiveButton("Включить",(d,w)->{config.enabled=true;config.paused=false;cloud.store.save(config);GhostCloudJobs.schedule(currentAccount,0);dismiss();update();})
                        .setNegativeButton(LocaleController.getString(R.string.Cancel),null).show();
                });
            });
            if(config.enabled){TextView disable=row(content,"Выключить облако",()->{config.enabled=false;cloud.store.save(config);GhostCloudJobs.schedule(currentAccount,0);dismiss();update();});disable.setTextColor(getThemedColor(Theme.key_text_RedRegular));}
            labels();
        }
        TextView row(LinearLayout content,String title,Runnable action){TextView v=label(content.getContext(),title,16,Theme.key_dialogTextBlack);v.setPadding(dp(20),dp(14),dp(20),dp(14));v.setBackground(Theme.getSelectorDrawable(false));v.setOnClickListener(view->action.run());content.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
        TextCheckCell check(LinearLayout content,String title,boolean initial,Consumer<Boolean> change){TextCheckCell cell=new TextCheckCell(getContext(),getResourceProvider());cell.setTextAndCheck(title,initial,false);cell.setOnClickListener(v->{boolean value=!cell.isChecked();cell.setChecked(value);change.accept(value);});content.addView(cell,new LinearLayout.LayoutParams(-1,-2));return cell;}
        String time(int minutes){return String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);}
        void labels(){folders.setText("Папки\n"+(config.camera?"Камера"+(config.folders.isEmpty()?"":" и ещё "+config.folders.size()):config.folders.size()+" выбрано"));period.setText("Период\n"+(config.period==0?"Только новые фотографии":config.period==2?"Вся галерея":"С "+new SimpleDateFormat("dd.MM.yyyy",Locale.getDefault()).format(new Date(config.since))));window.setText("Дни и время\n"+(config.scheduled?Integer.bitCount(config.days)+" дней · "+(config.start==config.end?"Круглосуточно":time(config.start)+"–"+time(config.end))+(config.start>config.end?" · через полночь":""):"Без расписания"));}
        void choosePeriod(){new AlertDialog.Builder(getContext(),getResourceProvider()).setTitle("Период копирования").setItems(new String[]{"Только новые фотографии","Начиная с даты","Вся галерея"},(d,index)->{
            if(index==1){Calendar date=Calendar.getInstance();date.setTimeInMillis(config.since);new DatePickerDialog(getContext(),(view,y,m,day)->{Calendar c=Calendar.getInstance();c.set(y,m,day,0,0,0);c.set(Calendar.MILLISECOND,0);config.period=1;config.since=c.getTimeInMillis();labels();},date.get(Calendar.YEAR),date.get(Calendar.MONTH),date.get(Calendar.DAY_OF_MONTH)).show();}
            else{if(index==0&&config.period!=0)config.since=System.currentTimeMillis();config.period=index;labels();}
        }).show();}
        void chooseFolders(){
            ArrayList<GhostCloudMedia.Source> sources=GhostCloudMedia.query();LinkedHashMap<String,String> albums=GhostCloudMedia.folders(sources);
            LinearLayout content=new LinearLayout(getContext());content.setOrientation(LinearLayout.VERTICAL);
            check(content,"Камера",config.camera,value->{config.camera=value;labels();});
            java.util.HashSet<String> cameraFolders=new java.util.HashSet<>();for(GhostCloudMedia.Source source:sources)if(source.camera)cameraFolders.add(source.folder);
            for(java.util.Map.Entry<String,String> album:albums.entrySet())if(!cameraFolders.contains(album.getKey()))check(content,album.getValue(),config.folders.contains(album.getKey()),value->{if(value)config.folders.add(album.getKey());else config.folders.remove(album.getKey());});
            ScrollView list=new ScrollView(getContext());list.addView(content);
            new AlertDialog.Builder(getContext(),getResourceProvider()).setTitle("Папки с фотографиями").setView(list).setPositiveButton(LocaleController.getString(R.string.OK),(d,w)->labels()).show();
        }
        void chooseDays(){
            LinearLayout content=new LinearLayout(getContext());content.setOrientation(LinearLayout.VERTICAL);
            String[] days={"Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье"};
            for(int i=0;i<7;i++){final int bit=1<<i;check(content,days[i],(config.days&bit)!=0,value->{if(value)config.days|=bit;else config.days&=~bit;});}
            new AlertDialog.Builder(getContext(),getResourceProvider()).setTitle("Дни начала загрузки").setView(content).setPositiveButton("Время",(d,w)->{
                new TimePickerDialog(getContext(),(v,h,m)->{config.start=h*60+m;new TimePickerDialog(getContext(),(view,hh,mm)->{config.end=hh*60+mm;config.scheduled=true;scheduleCell.setChecked(true);labels();},config.end/60,config.end%60,true).show();},config.start/60,config.start%60,true).show();
            }).setNegativeButton(LocaleController.getString(R.string.Cancel),(d,w)->labels()).show();
        }
    }
}
