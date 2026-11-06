package org.telegram.ui;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.widget.MediaController;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/** Swipe gallery: originals are read in place, remote files are downloaded only on demand. */
final class GhostCloudGalleryPreview extends Dialog implements NotificationCenter.NotificationCenterDelegate {
    private final GhostCloud cloud;private final ArrayList<GhostCloudStore.Photo> photos;private final Consumer<GhostCloudStore.Photo> details;
    private GhostCloudStore.Photo photo;private MessageObject message;private String fileName;
    private final MediaFrame media;private final TextView title,status,counter,previous,next;private final ImageView actions;
    private final DispatchQueue worker=new DispatchQueue("cloudPreview");
    private int index;private volatile int generation;private volatile boolean closed;private VideoView video;private float zoom=1;
    private GhostCloudGalleryPreview(BaseFragment fragment,GhostCloud cloud,ArrayList<GhostCloudStore.Photo> photos,int index,Consumer<GhostCloudStore.Photo> details){
        super(fragment.getParentActivity(),android.R.style.Theme_Black_NoTitleBar_Fullscreen);this.cloud=cloud;this.photos=photos;this.index=index;this.details=details;
        LinearLayout root=new LinearLayout(getContext());root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff000000);
        LinearLayout bar=new LinearLayout(getContext());bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView close=new ImageView(getContext());close.setImageResource(R.drawable.ic_ab_back);close.setColorFilter(-1);close.setScaleType(ImageView.ScaleType.CENTER);close.setContentDescription("Закрыть");close.setOnClickListener(v->dismiss());bar.addView(close,new LinearLayout.LayoutParams(dp(52),dp(56)));
        title=label(15);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        actions=new ImageView(getContext());actions.setImageResource(R.drawable.ic_ab_other);actions.setColorFilter(-1);actions.setScaleType(ImageView.ScaleType.CENTER);actions.setContentDescription("Действия с файлом");actions.setVisibility(details==null?View.GONE:View.VISIBLE);actions.setOnClickListener(v->{GhostCloudStore.Photo selected=photo;dismiss();if(selected!=null)details.accept(selected);});bar.addView(actions,new LinearLayout.LayoutParams(dp(52),dp(56)));root.addView(bar);
        media=new MediaFrame();media.setClipChildren(true);root.addView(media,new LinearLayout.LayoutParams(-1,0,1));
        status=label(14);status.setGravity(Gravity.CENTER);status.setOnClickListener(v->resolve());root.addView(status,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout navigation=new LinearLayout(getContext());navigation.setGravity(Gravity.CENTER_VERTICAL);previous=label(26);previous.setGravity(Gravity.CENTER);previous.setText("‹");previous.setContentDescription("Предыдущий файл");previous.setOnClickListener(v->move(-1));navigation.addView(previous,new LinearLayout.LayoutParams(dp(64),dp(50)));
        counter=label(13);counter.setGravity(Gravity.CENTER);navigation.addView(counter,new LinearLayout.LayoutParams(0,-2,1));next=label(26);next.setGravity(Gravity.CENTER);next.setText("›");next.setContentDescription("Следующий файл");next.setOnClickListener(v->move(1));navigation.addView(next,new LinearLayout.LayoutParams(dp(64),dp(50)));root.addView(navigation);setContentView(root);
    }
    private int dp(float n){return AndroidUtilities.dp(n);}private TextView label(int size){TextView t=new TextView(getContext());t.setTextSize(size);t.setTextColor(-1);return t;}
    static void open(BaseFragment fragment,GhostCloud cloud,ArrayList<GhostCloudStore.Photo> photos,int index,Consumer<GhostCloudStore.Photo> details){
        if(fragment.getParentActivity()==null||cloud==null||index<0||index>=photos.size())return;
        GhostCloudGalleryPreview viewer=new GhostCloudGalleryPreview(fragment,cloud,photos,index,details);fragment.showDialog(viewer);if(!viewer.isShowing())return;viewer.getWindow().setLayout(-1,-1);viewer.resolve();
    }
    private void move(int delta){int target=index+delta;if(target<0||target>=photos.size())return;index=target;resolve();}
    private void observe(boolean add){NotificationCenter center=NotificationCenter.getInstance(cloud.account);for(int id:new int[]{NotificationCenter.fileLoaded,NotificationCenter.fileLoadFailed,NotificationCenter.fileLoadProgressChanged}){if(add)center.addObserver(this,id);else center.removeObserver(this,id);}}
    private void resolve(){
        final int request=++generation;observe(false);if(video!=null){video.stopPlayback();video=null;}zoom=1;message=null;fileName=null;photo=photos.get(index);actions.setEnabled(false);
        media.removeAllViews();media.addView(new ProgressBar(getContext()),LayoutHelper.createFrame(40,40,Gravity.CENTER));status.setVisibility(View.VISIBLE);status.setText("Открываем оригинал…");
        title.setText(photo.name==null?"Файл":photo.name);counter.setText((index+1)+" / "+photos.size()+" · "+new java.text.SimpleDateFormat("dd.MM.yyyy",Locale.getDefault()).format(new Date(photo.taken)));previous.setEnabled(index>0);previous.setAlpha(index>0?1:.3f);next.setEnabled(index+1<photos.size());next.setAlpha(index+1<photos.size()?1:.3f);
        final GhostCloudStore.Photo selected=photo;
        worker.postRunnable(()->{
            if(closed||request!=generation)return;
            try{
                GhostCloudStore.Photo full=cloud.store.get(selected.hash);if(full==null)full=selected;
                TLRPC.Message raw=cloud.store.decode(full.message!=null?full.message:selected.message);final MessageObject decoded=raw==null?null:new MessageObject(cloud.account,raw,false,false);
                final String local=GhostCloudMedia.findOriginal(cloud.store,full);final GhostCloudStore.Photo current=full;
                if(closed||request!=generation)return;
                AndroidUtilities.runOnUIThread(()->{
                    if(closed||request!=generation)return;photo=current;message=decoded;actions.setEnabled(true);
                    fileName=decoded==null||decoded.getDocument()==null?null:FileLoader.getAttachFileName(decoded.getDocument());
                    if(local!=null){display(Uri.parse(local),request);if(decoded!=null)Utilities.globalQueue.postRunnable(()->GhostCloudMedia.releaseReplicas(cloud.account,cloud.store,current,decoded.getDocument()));return;}
                    if(message==null||message.getDocument()==null){status.setText("Оригинал недоступен. Файл ещё не сохранён в облако.");media.removeAllViews();return;}
                    java.io.File file=FileLoader.getInstance(cloud.account).getPathToAttach(message.getDocument(),true);
                    if(file.isFile()){display(Uri.fromFile(file),request);return;}
                    status.setText("Загружаем для просмотра в кэш…");observe(true);FileLoader.getInstance(cloud.account).loadFile(message.getDocument(),message,FileLoader.PRIORITY_HIGH,1);
                });
            }catch(Exception error){FileLog.e(error);AndroidUtilities.runOnUIThread(()->{if(!closed&&request==generation){media.removeAllViews();status.setText("Не удалось открыть файл. Нажмите, чтобы повторить.");}});}
        });
    }
    private void display(Uri uri,int request){
        if(closed||generation!=request)return;media.removeAllViews();status.setVisibility(View.GONE);
        if(photo.mime!=null&&photo.mime.startsWith("video/")){
            VideoView player=new VideoView(getContext());video=player;media.addView(player,LayoutHelper.createFrame(-1,-1,Gravity.CENTER));MediaController controls=new MediaController(getContext());controls.setAnchorView(player);player.setMediaController(controls);player.setVideoURI(uri);
            player.setOnPreparedListener(mp->{if(!closed&&request==generation)player.start();});player.setOnErrorListener((mp,what,extra)->{if(!closed&&request==generation){status.setVisibility(View.VISIBLE);status.setText("Не удалось воспроизвести этот формат видео");}return true;});
        }else{
            BackupImageView image=new BackupImageView(getContext());image.getImageReceiver().setAspectFit(true);media.addView(image,LayoutHelper.createFrame(-1,-1));
            worker.postRunnable(()->{if(closed||request!=generation)return;Bitmap bitmap=ImageLoader.loadBitmap(null,uri,Math.min(3000,AndroidUtilities.displaySize.x*2),Math.min(3000,AndroidUtilities.displaySize.y*2),true);
                AndroidUtilities.runOnUIThread(()->{if(closed||request!=generation){if(bitmap!=null)bitmap.recycle();return;}if(bitmap!=null)image.setImageBitmap(bitmap);else{status.setVisibility(View.VISIBLE);status.setText("Не удалось прочитать изображение");}});
            });
            ScaleGestureDetector scale=new ScaleGestureDetector(getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(1,Math.min(6,zoom*detector.getScaleFactor()));transform(image,0,0);return true;}});
            GestureDetector gestures=new GestureDetector(getContext(),new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){zoom=zoom>1.05f?1:2.5f;image.setTranslationX(0);image.setTranslationY(0);transform(image,0,0);return true;}@Override public boolean onScroll(MotionEvent first,MotionEvent now,float dx,float dy){if(zoom>1.05f)transform(image,-dx,-dy);return true;}});
            image.setOnTouchListener((v,event)->{scale.onTouchEvent(event);gestures.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_UP&&zoom<1.05f){zoom=1;transform(image,0,0);}return true;});
        }
    }
    private void transform(View image,float dx,float dy){image.setScaleX(zoom);image.setScaleY(zoom);float x=image.getWidth()*(zoom-1)/2,y=image.getHeight()*(zoom-1)/2;image.setTranslationX(Math.max(-x,Math.min(x,image.getTranslationX()+dx)));image.setTranslationY(Math.max(-y,Math.min(y,image.getTranslationY()+dy)));}
    private final class MediaFrame extends FrameLayout {
        float downX,downY;boolean multi;
        MediaFrame(){super(GhostCloudGalleryPreview.this.getContext());}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();multi=false;}if(e.getPointerCount()>1)multi=true;if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!multi&&zoom<1.05f){float dx=Math.abs(e.getX()-downX),dy=Math.abs(e.getY()-downY);if(dx>dp(36)&&dx>dy*1.5f||dy>dp(90)&&dy>dx*1.5f)return true;}return super.onInterceptTouchEvent(e);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_UP&&!multi&&zoom<1.05f){float dx=e.getX()-downX,dy=e.getY()-downY;if(Math.abs(dx)>dp(48)&&Math.abs(dx)>Math.abs(dy)*1.3f)move(dx<0?1:-1);else if(dy>dp(120)&&dy>Math.abs(dx)*1.5f)dismiss();}return true;}
    }
    @Override public void didReceivedNotification(int id,int account,Object... args){
        if(closed||message==null||args.length==0||!java.util.Objects.equals(fileName,args[0]))return;
        if(id==NotificationCenter.fileLoaded){observe(false);display(Uri.fromFile(FileLoader.getInstance(cloud.account).getPathToAttach(message.getDocument(),true)),generation);}
        else if(id==NotificationCenter.fileLoadFailed){observe(false);media.removeAllViews();status.setText("Загрузка не завершена. Нажмите, чтобы повторить.");}
        else{long[] bytes=ImageLoader.getInstance().getFileProgressSizes(fileName);if(bytes!=null&&bytes[1]>0)status.setText("Загрузка · "+(int)(100*bytes[0]/bytes[1])+"%");}
    }
    @Override public void dismiss(){if(closed)return;closed=true;generation++;if(video!=null)video.stopPlayback();observe(false);worker.recycle();super.dismiss();}
}
