package org.telegram.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;

/** A full lightweight index for fast scrolling; message blobs and pixels are fetched on demand. */
final class GhostCloudGallery {
    final LinearLayout view;
    private final GhostCloudActivity fragment;private final GhostCloud cloud;private final Consumer<GhostCloudStore.Photo> details;
    private final DispatchQueue queue=new DispatchQueue("cloudGallery"),thumbs=new DispatchQueue("cloudThumbnails");
    private final RecyclerListView grid;private final GridLayoutManager layout;private final GridAdapter adapter=new GridAdapter();
    private final ArrayList<Object> cells=new ArrayList<>();private final ArrayList<GhostCloudStore.Photo> photos=new ArrayList<>();
    private final TextView counter;private final TextView[] tabs=new TextView[3];
    private final android.util.LruCache<String,MessageObject> messages=new android.util.LruCache<>(96);
    private final android.util.LruCache<String,Bitmap> bitmaps=new android.util.LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    private final SimpleDateFormat date=new SimpleDateFormat("dd MMMM yyyy",Locale.getDefault()),month=new SimpleDateFormat("LLL yyyy",Locale.getDefault());
    private int kind,status,columns,generation;private boolean oldest,loading,resetPosition;private volatile boolean destroyed;
    private long from,until,revision=-1;private String search="";private Long pendingDate;
    private final Runnable searchChanged=()->reload(true);
    GhostCloudGallery(Context context,GhostCloudActivity fragment,GhostCloud cloud,Consumer<GhostCloudStore.Photo> details){
        this.fragment=fragment;this.cloud=cloud;this.details=details;
        kind=Math.max(0,Math.min(2,cloud.store.preferences.getInt("gallery_kind",0)));status=Math.max(0,Math.min(3,cloud.store.preferences.getInt("gallery_status",0)));
        columns=cloud.store.preferences.getInt("gallery_columns",3);if(columns!=2&&columns!=3&&columns!=5)columns=3;
        oldest=cloud.store.preferences.getBoolean("gallery_oldest",false);from=cloud.store.preferences.getLong("gallery_from",0);until=cloud.store.preferences.getLong("gallery_until",0);
        view=new LinearLayout(context);view.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar=new LinearLayout(context);bar.setPadding(dp(12),dp(6),dp(12),dp(4));
        String[] names={"Все","Фото","Видео"};
        for(int i=0;i<3;i++){final int type=i;TextView tab=text(context,15);tab.setText(names[i]);tab.setGravity(Gravity.CENTER);tab.setOnClickListener(v->{kind=type;change();});bar.addView(tab,new LinearLayout.LayoutParams(0,dp(36),1));tabs[i]=tab;}
        view.addView(bar);counter=text(context,12);counter.setText("Загрузка галереи…");counter.setPadding(dp(16),dp(5),dp(16),dp(9));counter.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));counter.setOnClickListener(v->filters());view.addView(counter);
        FrameLayout frame=new FrameLayout(context);grid=new RecyclerListView(context);grid.setItemAnimator(null);grid.setHasFixedSize(true);grid.setVerticalScrollBarEnabled(false);grid.setFastScrollEnabled(RecyclerListView.FastScroll.LETTER_TYPE);
        layout=new GridLayoutManager(context,columns);layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup(){@Override public int getSpanSize(int position){return position>=cells.size()||cells.get(position) instanceof String?columns:1;}});
        grid.setLayoutManager(layout);grid.setAdapter(adapter);
        grid.setOnItemClickListener((v,position)->{if(position>=0&&position<cells.size()&&cells.get(position) instanceof GhostCloudStore.Photo)open((GhostCloudStore.Photo)cells.get(position));});
        grid.setOnItemLongClickListener((v,position)->{if(position<0||position>=cells.size()||!(cells.get(position) instanceof GhostCloudStore.Photo))return false;full((GhostCloudStore.Photo)cells.get(position),details);return true;});
        frame.addView(grid,LayoutHelper.createFrame(-1,-1));view.addView(frame,new LinearLayout.LayoutParams(-1,0,1));paintTabs();
    }
    private int dp(float value){return AndroidUtilities.dp(value);}private int color(int key){return Theme.getColor(key,fragment.getResourceProvider());}
    private TextView text(Context context,int size){TextView t=new TextView(context);t.setTextSize(size);t.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));return t;}
    private void paintTabs(){for(int i=0;i<tabs.length;i++){boolean selected=i==kind;tabs[i].setTextColor(color(selected?Theme.key_windowBackgroundWhiteBlueText:Theme.key_windowBackgroundWhiteGrayText));tabs[i].setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18),selected?Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlueText),.12f):0,color(Theme.key_listSelector)));}}
    void search(String query){search=query;generation++;revision=-1;resetPosition=true;AndroidUtilities.cancelRunOnUIThread(searchChanged);AndroidUtilities.runOnUIThread(searchChanged,250);}
    void refresh(){if(!loading&&revision!=cloud.store.galleryRevision.get())reload(false);}
    private void change(){
        cloud.store.preferences.edit().putInt("gallery_kind",kind).putInt("gallery_status",status).putInt("gallery_columns",columns).putBoolean("gallery_oldest",oldest).putLong("gallery_from",from).putLong("gallery_until",until).apply();
        generation++;revision=-1;paintTabs();reload(true);
    }
    private void reload(boolean top){
        resetPosition|=top;if(destroyed||loading)return;loading=true;
        final int seq=generation,k=kind,s=status;final long start=from,end=until,version=cloud.store.galleryRevision.get();final boolean ascending=oldest;final String query=search;
        queue.postRunnable(()->{
            ArrayList<GhostCloudStore.Photo> result;try{result=cloud.store.gallery(k,s,start,end,ascending,query);}catch(Exception error){FileLog.e(error);AndroidUtilities.runOnUIThread(()->{loading=false;if(!destroyed)counter.setText("Не удалось прочитать галерею. Нажмите для смены фильтров.");});return;}
            ArrayList<Object> rows=new ArrayList<>();SimpleDateFormat formatter=new SimpleDateFormat("dd MMMM yyyy",Locale.getDefault());String previous=null;int videos=0;long bytes=0;
            for(GhostCloudStore.Photo p:result){String day=p.taken<=0?"Без даты":formatter.format(new Date(p.taken));if(!day.equals(previous)){rows.add(day);previous=day;}rows.add(p);if(p.mime!=null&&p.mime.startsWith("video/"))videos++;bytes+=p.size;}
            final int videoCount=videos;final long size=bytes;
            AndroidUtilities.runOnUIThread(()->{
                loading=false;if(destroyed)return;if(seq!=generation){reload(true);return;}
                int first=layout.findFirstVisibleItemPosition();View firstView=layout.findViewByPosition(first);int offset=firstView==null?0:layout.getDecoratedTop(firstView);
                Object anchor=first>=0&&first<cells.size()?cells.get(first):null;String key=anchor instanceof GhostCloudStore.Photo?((GhostCloudStore.Photo)anchor).hash:anchor instanceof String?(String)anchor:null;
                photos.clear();photos.addAll(result);cells.clear();cells.addAll(rows);
                if(cells.isEmpty())cells.add("По этим условиям ничего не найдено. Измените фильтры или добавьте файлы в облако.");
                revision=version;adapter.notifyDataSetChanged();grid.setFastScrollVisible(photos.size()>30);
                counter.setText("Фото: "+(result.size()-videoCount)+" · Видео: "+videoCount+" · "+AndroidUtilities.formatFileSize(size)+(status==0?"":" · "+new String[]{"","Сохранённые","В очереди","Ошибки"}[status])+(start>0||end>0?" · Период выбран":""));
                if(resetPosition){layout.scrollToPositionWithOffset(0,0);resetPosition=false;}else if(key!=null){for(int i=0;i<cells.size();i++){Object item=cells.get(i);if(key.equals(item instanceof GhostCloudStore.Photo?((GhostCloudStore.Photo)item).hash:item)){layout.scrollToPositionWithOffset(i,offset);break;}}}
                if(pendingDate!=null){long value=pendingDate;pendingDate=null;scrollToDate(value);}
            });
        });
    }
    void filters(){
        if(fragment.getParentActivity()==null)return;
        new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Показать в облаке")
            .setItems(new String[]{"Все файлы","Сохранённые в облаке","В очереди","С ошибками"},(d,i)->{status=i;change();})
            .setNegativeButton("Период…",(d,w)->period()).show();
    }
    void density(){new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Размер миниатюр").setItems(new String[]{"Крупные · 2 в ряд","Обычные · 3 в ряд","Мелкие · 5 в ряд"},(d,i)->{int first=layout.findFirstVisibleItemPosition();columns=new int[]{2,3,5}[i];layout.setSpanCount(columns);adapter.notifyDataSetChanged();if(first>=0)layout.scrollToPositionWithOffset(first,0);cloud.store.preferences.edit().putInt("gallery_columns",columns).apply();}).show();}
    void sorting(){new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Порядок по дате съёмки").setItems(new String[]{"Сначала новые","Сначала старые"},(d,i)->{oldest=i==1;change();}).show();}
    void period(){new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Период просмотра").setItems(new String[]{"За всё время","Этот месяц","Этот год","Выбрать период…"},(d,i)->{
        Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        if(i==0){from=until=0;change();}else if(i<3){c.set(Calendar.DAY_OF_MONTH,1);if(i==2)c.set(Calendar.MONTH,0);from=c.getTimeInMillis();until=0;change();}
        else pickDate(from>0?from:System.currentTimeMillis(),"Начало периода",a->pickDate(until>0?until-86400000L:System.currentTimeMillis(),"Конец периода",b->{Calendar last=Calendar.getInstance();last.setTimeInMillis(b);last.add(Calendar.DAY_OF_MONTH,1);if(last.getTimeInMillis()<=a){android.widget.Toast.makeText(view.getContext(),"Конец периода должен быть не раньше начала",android.widget.Toast.LENGTH_SHORT).show();return;}from=a;until=last.getTimeInMillis();change();}));
    }).show();}
    void jump(){pickDate(System.currentTimeMillis(),"Перейти к дате",value->{if(loading){pendingDate=value;return;}scrollToDate(value);});}
    private void pickDate(long selected,String title,Consumer<Long> result){Calendar c=Calendar.getInstance();c.setTimeInMillis(selected);DatePickerDialog dialog=new DatePickerDialog(fragment.getParentActivity(),(v,y,m,d)->{Calendar value=Calendar.getInstance();value.set(y,m,d,0,0,0);value.set(Calendar.MILLISECOND,0);result.accept(value.getTimeInMillis());},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));dialog.setTitle(title);dialog.show();}
    private void scrollToDate(long value){int best=-1;long distance=Long.MAX_VALUE;for(int i=0;i<cells.size();i++)if(cells.get(i) instanceof GhostCloudStore.Photo){long diff=Math.abs(((GhostCloudStore.Photo)cells.get(i)).taken-value);if(diff<distance){distance=diff;best=i;}}if(best>=0)layout.scrollToPositionWithOffset(best,0);}
    private void open(GhostCloudStore.Photo p){int index=photos.indexOf(p);if(index>=0)GhostCloudPreview.open(fragment,cloud,new ArrayList<>(photos),index,details);}
    private void full(GhostCloudStore.Photo p,Consumer<GhostCloudStore.Photo> callback){thumbs.postRunnable(()->{try{GhostCloudStore.Photo full=cloud.store.get(p.hash);AndroidUtilities.runOnUIThread(()->{if(!destroyed&&full!=null)callback.accept(full);});}catch(Exception error){FileLog.e(error);}});}
    private String cacheKey(GhostCloudStore.Photo p){return p.hash+":"+p.event+":"+p.state;}
    private void bindImage(Tile tile,GhostCloudStore.Photo p){
        String key=cacheKey(p);tile.bound=key;tile.image.setImageDrawable(Theme.createRoundRectDrawable(0,color(Theme.key_windowBackgroundGray)));
        MessageObject known=messages.get(key);if(known!=null&&remote(tile,known))return;
        Bitmap knownBitmap=bitmaps.get(p.hash);if(knownBitmap!=null){tile.image.setImageBitmap(knownBitmap);return;}
        thumbs.postRunnable(()->{
            try{
            if(destroyed||!key.equals(tile.bound))return;
            GhostCloudStore.Photo full=cloud.store.get(p.hash);if(full==null)return;TLRPC.Message raw=cloud.store.decode(full.message);MessageObject m=raw==null?null:new MessageObject(cloud.account,raw,false,false);
            if(m!=null&&m.getDocument()!=null&&FileLoader.getClosestPhotoSizeWithSize(m.getDocument().thumbs,320)!=null){messages.put(key,m);AndroidUtilities.runOnUIThread(()->{if(!destroyed&&key.equals(tile.bound))remote(tile,m);});return;}
            Bitmap bitmap=null;try{if(full.uri!=null){Uri uri=Uri.parse(full.uri);if(Build.VERSION.SDK_INT>=29&&"content".equals(uri.getScheme()))bitmap=view.getContext().getContentResolver().loadThumbnail(uri,new android.util.Size(256,256),null);else if(full.mime==null||!full.mime.startsWith("video/"))bitmap=ImageLoader.loadBitmap(null,uri,256,256,true);}}catch(Exception ignored){}
            if(bitmap!=null){bitmaps.put(p.hash,bitmap);final Bitmap thumb=bitmap;AndroidUtilities.runOnUIThread(()->{if(!destroyed&&key.equals(tile.bound))tile.image.setImageBitmap(thumb);});}
            }catch(Exception error){FileLog.e(error);}
        });
    }
    private boolean remote(Tile tile,MessageObject m){TLRPC.Document doc=m.getDocument();if(doc==null)return false;TLRPC.PhotoSize thumb=FileLoader.getClosestPhotoSizeWithSize(doc.thumbs,320);if(thumb==null)return false;tile.image.setImage(ImageLocation.getForDocument(thumb,doc),"160_160",(android.graphics.drawable.Drawable)null,m);return true;}
    private final class Tile extends FrameLayout {
        final BackupImageView image;final TextView badge;volatile String bound;
        Tile(Context context){super(context);setPadding(dp(1),dp(1),dp(1),dp(1));image=new BackupImageView(context);image.getImageReceiver().setCurrentAccount(cloud.account);image.getImageReceiver().setCrossfadeAlpha((byte)0);addView(image,LayoutHelper.createFrame(-1,-1));badge=text(context,11);badge.setTextColor(-1);badge.setPadding(dp(4),dp(2),dp(4),dp(2));badge.setBackground(Theme.createRoundRectDrawable(dp(5),0x99000000));addView(badge,LayoutHelper.createFrame(-2,-2,Gravity.BOTTOM|Gravity.LEFT,4,0,4,4));}
        @Override protected void onMeasure(int w,int h){super.onMeasure(w,MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w),MeasureSpec.EXACTLY));}
    }
    private final class GridAdapter extends RecyclerListView.FastScrollAdapter {
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder){int p=holder.getAdapterPosition();return p>=0&&p<cells.size()&&cells.get(p) instanceof GhostCloudStore.Photo;}
        @Override public int getItemCount(){return cells.size();}
        @Override public int getItemViewType(int p){return cells.get(p) instanceof String?0:1;}
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){View cell;if(type==0){TextView title=text(parent.getContext(),14);title.setPadding(dp(16),dp(16),dp(16),dp(10));cell=title;}else cell=new Tile(parent.getContext());cell.setLayoutParams(new RecyclerView.LayoutParams(-1,type==0?-2:dp(120)));return new RecyclerView.ViewHolder(cell){};}
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){Object value=cells.get(position);if(value instanceof String){((TextView)holder.itemView).setText((String)value);return;}GhostCloudStore.Photo p=(GhostCloudStore.Photo)value;Tile tile=(Tile)holder.itemView;bindImage(tile,p);boolean video=p.mime!=null&&p.mime.startsWith("video/");String label=GhostCloudStore.DONE.equals(p.state)?video?"▶ "+AndroidUtilities.formatFileSize(p.size):"":GhostCloudStore.ERROR.equals(p.state)?"Ошибка":GhostCloudStore.SENDING.equals(p.state)?"↑ Загружается":GhostCloudStore.DELETING.equals(p.state)?"Удаляется":"В очереди";tile.badge.setText(label);tile.badge.setVisibility(label.isEmpty()?View.GONE:View.VISIBLE);tile.setContentDescription(p.name+" · "+date.format(new Date(p.taken)));}
        @Override public void onViewRecycled(RecyclerView.ViewHolder holder){if(holder.itemView instanceof Tile){Tile tile=(Tile)holder.itemView;tile.bound=null;tile.image.getImageReceiver().cancelLoadImage();}super.onViewRecycled(holder);}
        @Override public String getLetter(int position){for(int i=Math.max(0,position);i<cells.size();i++)if(cells.get(i) instanceof GhostCloudStore.Photo)return month.format(new Date(((GhostCloudStore.Photo)cells.get(i)).taken));return "";}
        @Override public void getPositionForScrollProgress(RecyclerListView list,float progress,int[] position){position[0]=Math.max(0,Math.min(cells.size()-1,Math.round(Math.max(0,cells.size()-columns)*progress)));position[1]=0;}
        @Override public float getScrollProgress(RecyclerListView list){return Math.max(0,Math.min(1,layout.findFirstVisibleItemPosition()/(float)Math.max(1,cells.size()-grid.getChildCount())));}
    }
    void destroy(){destroyed=true;generation++;AndroidUtilities.cancelRunOnUIThread(searchChanged);queue.recycle();thumbs.recycle();messages.evictAll();bitmaps.evictAll();}
}
