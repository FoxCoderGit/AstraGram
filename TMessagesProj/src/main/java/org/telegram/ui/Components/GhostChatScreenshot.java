package org.telegram.ui.Components;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.TextSettingsCell;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native chat-cell rendering, read-only history access, bounded rendering buffers, gallery publication. */
public final class GhostChatScreenshot {
    private GhostChatScreenshot() { }

    public static void show(ChatActivity chat){
        if(chat.getParentActivity()==null)return;
        if(Build.VERSION.SDK_INT<29&&chat.getParentActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            chat.getParentActivity().requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},9484);
            return;
        }
        Calendar[] days={Calendar.getInstance(),Calendar.getInstance()};
        String key="ghost_screenshot_"+chat.getCurrentAccount()+"_"+chat.getDialogId();
        android.content.SharedPreferences prefs=MessagesController.getGlobalMainSettings();
        days[0].setTimeInMillis(prefs.getLong(key+"_from",days[0].getTimeInMillis()));
        days[1].setTimeInMillis(prefs.getLong(key+"_to",days[1].getTimeInMillis()));
        for(Calendar selected:days){if(selected.get(Calendar.YEAR)<2013||selected.get(Calendar.YEAR)>2037)selected.setTimeInMillis(System.currentTimeMillis());}
        SimpleDateFormat dates=new SimpleDateFormat("dd.MM.yyyy",Locale.getDefault());
        AlertDialog[] openDatePicker={null};
        LinearLayout content=new LinearLayout(chat.getParentActivity());content.setOrientation(LinearLayout.VERTICAL);
        for(int i=0;i<2;i++){
            final int index=i;
            TextSettingsCell row=new TextSettingsCell(chat.getParentActivity(),chat.getResourceProvider());
            row.setTextAndValue(i==0?"С даты":"По дату включительно",dates.format(days[i].getTime()),true);
            row.setBackground(Theme.getSelectorDrawable(false));content.addView(row,new LinearLayout.LayoutParams(-1,-2));
            row.setOnClickListener(v->{
                openDatePicker[0]=datePicker(chat,days[index],index==0?"Начало периода":"Конец периода",(year,month,day)->{
                    days[index].set(year,month,day);row.setTextAndValue(index==0?"С даты":"По дату включительно",dates.format(days[index].getTime()),true);
                });
                // Keep the period sheet underneath: BaseFragment.showDialog replaces its current dialog.
                openDatePicker[0].show();
            });
        }
        TextView hint=new TextView(chat.getParentActivity());hint.setText("Один длинный PNG в полном разрешении вашего экрана. Незагруженные вложения могут отображаться как превью.");
        hint.setTextSize(14);hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray,chat.getResourceProvider()));hint.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(16),AndroidUtilities.dp(24),AndroidUtilities.dp(8));content.addView(hint);
        AlertDialog settings=new AlertDialog.Builder(chat.getParentActivity(),chat.getResourceProvider()).setTitle("Скриншот переписки")
            .setView(content).setPositiveButton("Сохранить",(dialog,which)->{
                long from=startOfDay(days[0]),to=endOfDay(days[1]);
                if(from>=to){AlertsCreator.showSimpleAlert(chat,"Дата окончания должна быть не раньше даты начала.");return;}
                prefs.edit().putLong(key+"_from",days[0].getTimeInMillis()).putLong(key+"_to",days[1].getTimeInMillis()).apply();
                new Export(chat,(int)(from/1000),(int)(to/1000)).start();
            }).setNegativeButton("Отмена",null).create();
        chat.showDialog(settings,dialog->{if(openDatePicker[0]!=null)openDatePicker[0].dismiss();});
    }

    static long startOfDay(Calendar day){Calendar c=(Calendar)day.clone();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    static long endOfDay(Calendar day){Calendar c=(Calendar)day.clone();c.setTimeInMillis(startOfDay(day));c.add(Calendar.DAY_OF_MONTH,1);return c.getTimeInMillis();}
    private static AlertDialog datePicker(ChatActivity chat,Calendar selected,String title,AlertsCreator.DatePickerDelegate callback){
        int currentYear=Calendar.getInstance().get(Calendar.YEAR);
        return AlertsCreator.createDatePickerDialog(chat.getParentActivity(),2013-currentYear,2037-currentYear,0,
            selected.get(Calendar.DAY_OF_MONTH),selected.get(Calendar.MONTH),selected.get(Calendar.YEAR),title,false,callback).create();
    }

    private static final class Export {
        final int account,from,to;
        final long peer;
        final ArrayList<MessageObject> rows=new ArrayList<>();
        final HashMap<Integer,MessageObject> byId=new HashMap<>();
        final HashSet<Integer> seen=new HashSet<>();
        final android.app.Activity activity;
        final Theme.ResourcesProvider resources;
        final long topic,owner;
        final ArrayList<TLRPC.Message> localSnapshot=new ArrayList<>();
        final GhostBackgroundTasks.Task task;
        Bitmap headerImage;
        int sourceHeight;
        long sliceStarted;
        final DispatchQueue writer=new DispatchQueue("chatScreenshot");
        final ArrayList<Uri> saved=new ArrayList<>();
        final String name="AstraGram_chat_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        volatile boolean canceled;
        String failureReason;
        int requestId,index,part,used,nativeWidth,outputWidth,pageHeight;
        String day;
        float scale;
        FrameLayout host;
        View row;
        Bitmap page,wallpaper;
        GhostLongPng png;
        File pngFile;
        int writtenHeight,pendingWrites;
        Runnable resumeAfterWrite;
        final ArrayList<ChatMessageCell> cells=new ArrayList<>();
        final HashMap<Integer,View> prepared=new HashMap<>();
        int preparedUntil;
        ChatActionCell dateCell;
        final SimpleDateFormat dayFormat=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        Canvas canvas;
        Paint background;
        Export(ChatActivity chat,int from,int to){
            this.from=from;this.to=to;account=chat.getCurrentAccount();peer=chat.getDialogId();
            activity=chat.getParentActivity();resources=chat.getResourceProvider();topic=chat.getTopicId();owner=UserConfig.getInstance(account).getClientUserId();
            android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
            nativeWidth=Math.max(chat.getFragmentView().getWidth(),metrics.widthPixels);sourceHeight=Math.max(AndroidUtilities.dp(600),chat.getFragmentView().getHeight());
            if(topic!=0||GhostSimulationPolicy.virtual(peer))for(MessageObject m:chat.messages)localSnapshot.add(m.messageOwner);
            wallpaper=Bitmap.createBitmap(Math.max(1,nativeWidth),sourceHeight,Bitmap.Config.ARGB_8888);
            Canvas bg=new Canvas(wallpaper);bg.drawColor(Theme.getColor(Theme.key_chat_wallpaper,resources));
            Drawable drawable=chat.getGameWallpaper();if(drawable!=null){Rect old=new Rect(drawable.getBounds());drawable.setBounds(0,0,wallpaper.getWidth(),wallpaper.getHeight());drawable.draw(bg);drawable.setBounds(old);}
            View header=chat.getActionBar();if(header.getWidth()>0&&header.getHeight()>0){headerImage=Bitmap.createBitmap(header.getWidth(),header.getHeight(),Bitmap.Config.ARGB_8888);header.draw(new Canvas(headerImage));}
            task=GhostBackgroundTasks.create("Скриншот переписки",this::cancel);
        }
        void start(){android.widget.Toast.makeText(activity,"Скриншот сохраняется в фоне. Прогресс — в «Фоновых задачах»",android.widget.Toast.LENGTH_LONG).show();if(DialogObject.isEncryptedDialog(peer)||GhostSimulationPolicy.virtual(peer))loadLocal();else load(0);}
        boolean active(){if(canceled)return false;if(UserConfig.getInstance(account).getClientUserId()!=owner){cancel();return false;}return true;}
        void cancel(){
            if(canceled)return;canceled=true;task.finish(failureReason==null?"Отменено":failureReason,null);if(requestId!=0)ConnectionsManager.getInstance(account).cancelRequest(requestId,true);
            dispose();writer.postRunnable(()->{
                try{for(Uri uri:saved){try{chatContext().getContentResolver().delete(uri,null,null);}catch(Exception e){FileLog.e(e);}}saved.clear();}
                finally{closePng();writer.recycle();}
            });
        }
        android.content.Context chatContext(){return ApplicationLoader.applicationContext;}
        void fail(String message){if(canceled)return;failureReason=message;cancel();}
        void add(TLRPC.Message message){
            if(message==null||message instanceof TLRPC.TL_messageEmpty||message.date<from||message.date>=to||!seen.add(message.id))return;
            message.dialog_id=peer;
            MessageObject object=new MessageObject(account,message,false,true);rows.add(object);byId.put(message.id,object);
        }
        void load(int offset){
            if(!active())return;
            TLObject request;
            if(topic!=0){
                TLRPC.TL_messages_search q=new TLRPC.TL_messages_search();q.peer=MessagesController.getInstance(account).getInputPeer(peer);q.q="";q.filter=new TLRPC.TL_inputMessagesFilterEmpty();q.limit=100;q.offset_id=offset;q.min_date=from;q.max_date=to-1;q.top_msg_id=(int)topic;q.flags|=2;q.saved_reaction=null;request=q;
            }else{
                TLRPC.TL_messages_getHistory q=new TLRPC.TL_messages_getHistory();q.peer=MessagesController.getInstance(account).getInputPeer(peer);q.limit=100;q.offset_id=offset;q.offset_date=offset==0?to:0;request=q;
            }
            requestId=ConnectionsManager.getInstance(account).sendRequest(request,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(!active())return;
                task.progress("Загрузка истории · "+rows.size()+" сообщений",-1);
                if(error!=null||!(response instanceof TLRPC.messages_Messages)){fail(error==null?"Не удалось получить историю чата.":error.text);return;}
                TLRPC.messages_Messages result=(TLRPC.messages_Messages)response;
                MessagesController.getInstance(account).putUsers(result.users,false);MessagesController.getInstance(account).putChats(result.chats,false);
                int oldest=Integer.MAX_VALUE,oldestDate=Integer.MAX_VALUE;
                for(TLRPC.Message message:result.messages){if(message.id<=0)continue;oldest=Math.min(oldest,message.id);if(message.date>0)oldestDate=Math.min(oldestDate,message.date);add(message);}
                if(!result.messages.isEmpty()&&oldest!=Integer.MAX_VALUE&&oldest!=offset&&oldestDate>=from){final int next=oldest;AndroidUtilities.runOnUIThread(()->load(next));}else loadLocal();
            }));
        }
        void loadLocal(){
            if(!active())return;
            // Merge cached anti-recall messages as well; never modifies the real chat.
            if(topic!=0||GhostSimulationPolicy.virtual(peer)){for(TLRPC.Message m:localSnapshot)add(m);prepare();return;}
            MessagesStorage.getInstance(account).getStorageQueue().postRunnable(()->{
                ArrayList<TLRPC.Message> local=new ArrayList<>();Throwable failure=null;SQLiteCursor cursor=null;
                try{
                    cursor=MessagesStorage.getInstance(account).getDatabase().queryFinalized("SELECT data,custom_params FROM messages_v2 WHERE uid = ? AND date >= ? AND date < ? ORDER BY date,mid",peer,from,to);
                    while(cursor.next()&&!canceled){
                        NativeByteBuffer data=cursor.byteBufferValue(0);if(data==null)continue;
                        TLRPC.Message m;
                        try{m=TLRPC.Message.TLdeserialize(data,data.readInt32(false),false);if(m!=null)m.readAttachPath(data,UserConfig.getInstance(account).getClientUserId());}finally{data.reuse();}
                        if(m==null)continue;
                        NativeByteBuffer params=cursor.byteBufferValue(1);if(params!=null){try{MessageCustomParamsHelper.readLocalParams(m,params);}finally{params.reuse();}}
                        local.add(m);
                    }
                }catch(Throwable error){failure=error;}finally{if(cursor!=null)cursor.dispose();}
                final Throwable problem=failure;
                AndroidUtilities.runOnUIThread(()->{if(!active())return;if(problem!=null){FileLog.e(problem);fail("Не удалось прочитать локальную историю.");return;}mergeLocal(local,0);});
            });
        }
        void mergeLocal(ArrayList<TLRPC.Message> local,int offset){
            if(!active())return;long started=android.os.SystemClock.uptimeMillis();int i=offset;
            while(i<local.size()){add(local.get(i++));if(android.os.SystemClock.uptimeMillis()-started>=6)break;}
            if(i<local.size()){final int next=i;ApplicationLoader.applicationHandler.post(()->mergeLocal(local,next));}else prepare();
        }
        void prepare(){
            if(!active())return;
            if(rows.isEmpty()){fail("В выбранном периоде нет сообщений.");return;}
            writer.postRunnable(()->{
                rows.sort((a,b)->{int d=Integer.compare(a.messageOwner.date,b.messageOwner.date);return d!=0?d:a.getId()<0&&b.getId()<0?Integer.compare(b.getId(),a.getId()):Integer.compare(a.getId(),b.getId());});
                for(MessageObject message:rows)if(message.getReplyMsgId()!=0)message.replyMessageObject=byId.get(message.getReplyMsgId());
                ApplicationLoader.applicationHandler.post(()->loadReplies(0));
            });
        }
        void loadReplies(int offset){
            if(!active())return;
            if(offset>=rows.size()){beginRendering();return;}
            // The stock reply loader scans its input quadratically. Keep its batches
            // small without limiting how many messages the full export contains.
            int end=Math.min(rows.size(),offset+128);
            ArrayList<MessageObject> batch=new ArrayList<>(rows.subList(offset,end));
            MediaDataController.getInstance(account).loadReplyMessagesForMessages(batch,peer,0,topic,()->AndroidUtilities.runOnUIThread(()->loadReplies(end)),0,null);
        }
        boolean rendering;
        void beginRendering(){
            if(rendering||!active())return;rendering=true;
            try{
                if(nativeWidth<=0)throw new IllegalStateException("Chat width is not measured");
                outputWidth=nativeWidth;scale=1f;pageHeight=Math.min(8192,24*1024*1024/(outputWidth*4));
                host=new FrameLayout(activity);host.setAlpha(0);host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                ((ViewGroup)activity.getWindow().getDecorView()).addView(host,0,new ViewGroup.LayoutParams(nativeWidth,sourceHeight));
                BitmapShader shader=new BitmapShader(wallpaper,Shader.TileMode.CLAMP,Shader.TileMode.REPEAT);Matrix matrix=new Matrix();matrix.setScale(scale,scale);shader.setLocalMatrix(matrix);background=new Paint();background.setShader(shader);
                newPage();next();
            }catch(Throwable error){FileLog.e(error);fail("Не удалось подготовить изображение чата.");}
        }
        void newPage(){
            page=Bitmap.createBitmap(outputWidth,pageHeight,Bitmap.Config.ARGB_8888);canvas=new Canvas(page);
            int phase=writtenHeight%Math.max(1,(int)(wallpaper.getHeight()*scale));canvas.save();canvas.translate(0,-phase);canvas.drawRect(0,phase,outputWidth,pageHeight+phase,background);canvas.restore();used=0;
            if(part==0&&headerImage!=null){int height=(int)Math.ceil(headerImage.getHeight()*outputWidth/(float)headerImage.getWidth());canvas.drawBitmap(headerImage,null,new Rect(0,0,outputWidth,height),null);used=height;}
        }
        void next(){
            if(!active())return;
            if(android.os.SystemClock.uptimeMillis()-sliceStarted>=6){ApplicationLoader.applicationHandler.post(()->{sliceStarted=android.os.SystemClock.uptimeMillis();next();});return;}
            task.progress("Отрисовка "+index+" из "+rows.size(),rows.isEmpty()?0:(int)(index*95L/rows.size()));
            try{
                if(index>=rows.size()){flush(true,null);return;}
                MessageObject message=rows.get(index);
                String current=dayFormat.format(new Date(message.messageOwner.date*1000L));
                if(!current.equals(day)){
                    day=current;if(dateCell==null)dateCell=new ChatActionCell(activity,false,resources);dateCell.setCustomDate(message.messageOwner.date,false,false);
                    mount(dateCell);measure(dateCell);drawRow(dateCell,0,()->{row=null;ApplicationLoader.applicationHandler.post(this::next);});return;
                }
                if(index>=preparedUntil){prepareBatch();return;}
                row=prepared.get(index);
                drawRow(row,0,this::finishedRow);
            }catch(Throwable error){FileLog.e(error);fail("Ошибка отрисовки сообщения. Экспорт отменён.");}
        }
        // Keep a small native-cell pool attached so image decoding happens concurrently.
        // A batch has one shared deadline, never a delay for each individual message.
        void prepareBatch(){
            for(View old:prepared.values())if(old instanceof ChatActionCell)host.removeView(old);
            prepared.clear();int slot=0;
            preparedUntil=Math.min(rows.size(),index+16);
            prepareBatchPart(index,slot);
        }
        void prepareBatchPart(int start,int initialSlot){
            if(!active())return;int slot=initialSlot;long started=android.os.SystemClock.uptimeMillis();
            for(int i=start;i<preparedUntil;i++){
                if(i>start&&android.os.SystemClock.uptimeMillis()-started>=6){final int next=i,nextSlot=slot;ApplicationLoader.applicationHandler.post(()->prepareBatchPart(next,nextSlot));return;}
                MessageObject message=rows.get(i);View view;
                if(message.messageOwner instanceof TLRPC.TL_messageService){
                    ChatActionCell service=new ChatActionCell(activity,false,resources);
                    service.setMessageObject(message);view=service;
                }else{
                    if(slot==cells.size()){
                        ChatMessageCell cell=new ChatMessageCell(activity,account,false,null,resources);
                        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate(){});cell.isChat=peer<0;cell.setFullyDraw(true);
                        cell.getPhotoImage().setCrossfadeAlpha((byte)0);cells.add(cell);
                    }
                    ChatMessageCell cell=cells.get(slot++);cell.setMessageObject(message,null,false,false,false);view=cell;
                }
                mount(view);measure(view);
                if(view instanceof ChatMessageCell)((ChatMessageCell)view).setParentViewSize(nativeWidth,Math.max(view.getMeasuredHeight(),AndroidUtilities.dp(600)));
                prepared.put(i,view);
            }
            waitForBatch(android.os.SystemClock.uptimeMillis()+200);
        }
        void mount(View view){row=view;if(view.getParent()!=host)host.addView(view,new FrameLayout.LayoutParams(nativeWidth,-2));}
        void measure(View view){view.measure(View.MeasureSpec.makeMeasureSpec(nativeWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,nativeWidth,view.getMeasuredHeight());}
        void waitForBatch(long deadline){
            if(!active())return;
            boolean pending=false;
            for(View view:prepared.values())if(view instanceof ChatMessageCell){
                ChatMessageCell cell=(ChatMessageCell)view;MessageObject message=cell.getMessageObject();
                if((message.isPhoto()||message.isVideo()||message.isSticker())&&!cell.getPhotoImage().hasBitmapImage()){pending=true;break;}
            }
            if(pending&&android.os.SystemClock.uptimeMillis()<deadline){AndroidUtilities.runOnUIThread(()->waitForBatch(deadline),16);return;}
            next();
        }
        void finishedRow(){
            // These objects belong only to the export. Retain text for reply previews,
            // but release rendered layouts instead of keeping an entire chat's layouts.
            MessageObject message=rows.get(index);message.textLayoutBlocks=null;message.resetLayout();
            row=null;index++;ApplicationLoader.applicationHandler.post(this::next);
        }
        void drawRow(View view,int offset,Runnable done){
            if(!active())return;
            int total=(int)Math.ceil(view.getMeasuredHeight()*scale),remaining=total-offset;
            if(remaining<=0){done.run();return;}
            int slice=Math.min(remaining,pageHeight-used);
            canvas.save();canvas.clipRect(0,used,outputWidth,used+slice);canvas.translate(0,used-offset);canvas.scale(scale,scale);view.draw(canvas);canvas.restore();used+=slice;
            int nextOffset=offset+slice;
            if(nextOffset<total)flush(false,()->drawRow(view,nextOffset,done));else done.run();
        }
        void flush(boolean last,Runnable continuation){
            if(!active())return;
            final Bitmap original=page;final int height=used;page=null;canvas=null;++part;
            writtenHeight+=height;pendingWrites++;
            writer.postRunnable(()->{
                Throwable failure=null;
                try{
                    if(!canceled){
                        if(png==null){pngFile=new File(chatContext().getCacheDir(),name+".png.part");png=new GhostLongPng(pngFile,outputWidth,true);}
                        int[] stripe=new int[outputWidth*16];
                        for(int y=0;y<height&&!canceled;y+=16){int count=Math.min(16,height-y);original.getPixels(stripe,0,outputWidth,0,y,outputWidth,count);png.appendRows(stripe,count);}
                        if(last&&!canceled){png.finish();png=null;save(pngFile);pngFile.delete();pngFile=null;}
                    }
                }catch(Throwable error){failure=error;}finally{if(!original.isRecycled())original.recycle();}
                final Throwable problem=failure;
                AndroidUtilities.runOnUIThread(()->{
                    pendingWrites--;
                    if(!active())return;
                    if(problem!=null){FileLog.e(problem);fail("Не удалось сохранить изображение в галерею. Проверьте свободное место.");return;}
                    if(last){publish();return;}
                    if(resumeAfterWrite!=null){Runnable resume=resumeAfterWrite;resumeAfterWrite=null;resume.run();}
                });
            });
            if(!last){
                Runnable resume=()->{if(!active())return;try{newPage();continuation.run();}catch(Throwable error){FileLog.e(error);fail("Не удалось продолжить отрисовку изображения.");}};
                // At most two bitmap buffers exist: rendering overlaps PNG compression.
                if(pendingWrites<2)resume.run();else resumeAfterWrite=resume;
            }
        }
        void save(File image)throws Exception{
            ContentValues values=new ContentValues();String filename=name+".png";values.put(MediaStore.Images.Media.DISPLAY_NAME,filename);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            File legacy=null;
            if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/AstraGram");values.put(MediaStore.Images.Media.IS_PENDING,1);}
            else{File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"AstraGram");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Cannot create gallery folder");legacy=new File(folder,filename);values.put(MediaStore.Images.Media.DATA,legacy.getAbsolutePath());}
            Uri uri=chatContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new java.io.IOException("Gallery insert failed");saved.add(uri);
            try(InputStream input=new java.io.FileInputStream(image);OutputStream output=chatContext().getContentResolver().openOutputStream(uri)){if(output==null)throw new java.io.IOException("PNG write failed");byte[] bytes=new byte[131072];int count;while((count=input.read(bytes))!=-1){if(canceled)throw new java.io.IOException("Canceled");output.write(bytes,0,count);}}
        }
        void publish(){
            writer.postRunnable(()->{
                Throwable failure=null;
                try{if(!canceled&&Build.VERSION.SDK_INT>=29)for(Uri uri:saved){ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.IS_PENDING,0);chatContext().getContentResolver().update(uri,values,null,null);}}
                catch(Throwable error){failure=error;}
                final Throwable problem=failure;
                AndroidUtilities.runOnUIThread(()->{if(!active())return;if(problem!=null){FileLog.e(problem);fail("Не удалось опубликовать изображение в галерее.");return;}dispose();writer.recycle();final Uri result=saved.isEmpty()?null:saved.get(0);task.finish("Сохранён в галерею · альбом AstraGram",result==null?null:a->{try{a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(result,"image/png").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){android.widget.Toast.makeText(a,"Скриншот сохранён в альбоме AstraGram",android.widget.Toast.LENGTH_LONG).show();}});});
            });
        }
        void closePng(){if(png!=null){try{png.close();}catch(IOException e){FileLog.e(e);}png=null;}if(pngFile!=null){pngFile.delete();pngFile=null;}}
        void dispose(){
            if(host!=null){host.removeAllViews();AndroidUtilities.removeFromParent(host);host=null;row=null;}
            if(page!=null){page.recycle();page=null;canvas=null;}
            if(wallpaper!=null){wallpaper.recycle();wallpaper=null;}
            if(headerImage!=null){headerImage.recycle();headerImage=null;}
            prepared.clear();cells.clear();byId.clear();localSnapshot.clear();
        }
    }
}
