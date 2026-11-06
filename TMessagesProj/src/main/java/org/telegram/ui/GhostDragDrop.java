package org.telegram.ui;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.RecyclerListView;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Native Android drag transactions. No forwarding, private clipboard, or plugin runtime. */
public final class GhostDragDrop {
    private static final DispatchQueue queue=new DispatchQueue("ghostDragFiles");
    public static final class Item {
        public final File file;public final Uri uri;public final String name,mime;public final long size;public final MessageObject message;
        public org.telegram.tgnet.TLRPC.TL_document uploadDocument;
        public org.telegram.tgnet.TLRPC.TL_photo uploadPhoto;
        Item(File file,Uri uri,String name,String mime,MessageObject message){this.file=file;this.uri=uri;this.name=name;this.mime=mime;this.message=message;this.size=file.length();}
    }
    public static final class Payload {
        public final String token=UUID.randomUUID().toString();
        public final int sourceAccount;public final long sourceDialog;
        public final ArrayList<Item> items=new ArrayList<>();public final AtomicBoolean claimed=new AtomicBoolean();
        public String text;public boolean staged;public File stagedRoot;public Object targetContext;
        private java.lang.ref.WeakReference<View> sourceView;private float sourceAlpha=1f;
        Payload(int account,long dialog){sourceAccount=account;sourceDialog=dialog;}
    }
    private static volatile Payload active;
    public static boolean attachment(MessageObject m){return m!=null&&m.messageOwner!=null&&!m.isSending()&&!m.isSponsored()&&!m.isEphemeral()&&!m.isVoiceOnce()&&!(m.messageOwner.media instanceof org.telegram.tgnet.TLRPC.TL_messageMediaWebPage)&&(m.getDocument()!=null||m.messageOwner.media!=null&&m.messageOwner.media.photo!=null);}
    public static void notice(String text){AndroidUtilities.runOnUIThread(()->Toast.makeText(ApplicationLoader.applicationContext,text,Toast.LENGTH_LONG).show());}
    public static File local(MessageObject message)throws IOException {
        File file=FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        if(!file.isFile()&&message.messageOwner.attachPath!=null&&!message.messageOwner.attachPath.isEmpty())file=new File(message.messageOwner.attachPath);
        if(!file.isFile()||!file.canRead()||file.length()==0||message.getDocument()!=null&&message.getDocument().size>0&&file.length()!=message.getDocument().size)throw new IOException("Сначала полностью скачайте все выбранные вложения.");
        return file;
    }
    private static Payload from(DragEvent event){
        if(event.getLocalState() instanceof Payload)return (Payload)event.getLocalState();
        Payload p=active;
        if(p!=null&&event.getClipDescription()!=null&&android.text.TextUtils.equals("ghost-drag:"+p.token,event.getClipDescription().getLabel()))return p;
        return null;
    }
    public static boolean fileDescription(ClipDescription description){
        if(description==null)return false;
        for(int i=0;i<description.getMimeTypeCount();i++){String mime=description.getMimeType(i);if(!mime.startsWith("text/")||mime.equals(ClipDescription.MIMETYPE_TEXT_URILIST))return true;}
        return false;
    }
    public static boolean hasFileUris(ClipData data){if(data==null)return false;for(int i=0;i<data.getItemCount();i++){Uri uri=data.getItemAt(i).getUri();if(uri!=null&&"content".equals(uri.getScheme()))return true;}return false;}
    public static boolean allowed(){return GhostDragSettings.enabled()&&!SharedConfig.appLocked&&!SharedConfig.isWaitingForPasscodeEnter&&GhostLicenseBridge.allowed();}
    public static boolean accepts(ClipDescription d){return allowed()&&d!=null&&(fileDescription(d)?GhostDragSettings.get("files"):GhostDragSettings.get("text")||GhostDragSettings.get("files"));}

    public static final class Gesture {
        private final ChatActivity host;private long down;private float x,y;private boolean touching,preparing,starting,dragging,textCandidate;
        private int generation;private ArrayList<MessageObject> messages;private String text;private ChatMessageCell cell;private long delay;
        public Gesture(ChatActivity host){this.host=host;}
        public boolean onTouch(MotionEvent event){
            if(starting)return true;
            if(!allowed()||!host.ghostDragCanStart())return false;
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                reset();if(host.ghostFocusIsOpen())return false;
                down=SystemClock.uptimeMillis();x=event.getRawX();y=event.getRawY();
                text=GhostDragSettings.get("text")?host.ghostDragTextAt(x,y):null;
                textCandidate=text!=null&&!text.isEmpty();
                cell=host.ghostDragCellAt(x,y);
                messages=GhostDragSettings.get("files")&&cell!=null?host.ghostDragMessages(cell):null;
                touching=textCandidate||messages!=null&&!messages.isEmpty();
                delay=textCandidate||host.ghostDragSelectionActive()?350:1000;
                return textCandidate;
            }
            if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_DOWN){boolean consumed=textCandidate;reset();return consumed;}
            if(action==MotionEvent.ACTION_MOVE&&touching){
                float distance=(float)Math.hypot(event.getRawX()-x,event.getRawY()-y);
                if(distance>AndroidUtilities.dp(10)&&SystemClock.uptimeMillis()-down>=delay){if(!preparing)prepare();return true;}
                // Ordinary scrolling/swipe-to-reply takes priority before the hold threshold.
                if(distance>AndroidUtilities.dp(18)&&SystemClock.uptimeMillis()-down<delay){reset();return false;}
                return textCandidate;
            }
            return false;
        }
        public void reset(){touching=false;preparing=false;textCandidate=false;messages=null;text=null;cell=null;generation++;}
        private void prepare(){
            preparing=true;final int epoch=generation;final boolean isText=textCandidate;final String fragment=text;
            final ArrayList<MessageObject> originals=messages==null?new ArrayList<>():new ArrayList<>(messages);final ChatMessageCell sourceCell=cell;
            final int account=host.getCurrentAccount();final long dialog=host.getDialogId();
            queue.postRunnable(()->{
                Payload payload=new Payload(account,dialog);String error=null;
                try{
                    if(isText)payload.text=fragment;
                    else for(MessageObject message:originals){
                        if(!attachment(message))continue;
                        File file=local(message);
                        Uri uri=FileProvider.getUriForFile(ApplicationLoader.applicationContext,ApplicationLoader.getApplicationId()+".provider",file);
                        String mime=message.getDocument()!=null?message.getDocument().mime_type:"image/jpeg";
                        String name=message.getDocument()!=null?FileLoader.getDocumentFileName(message.getDocument()):file.getName();
                        payload.items.add(new Item(file,uri,name==null||name.isEmpty()?file.getName():name,mime==null?"application/octet-stream":mime,message));
                    }
                    if(!isText&&payload.items.isEmpty())throw new IOException("В выбранных сообщениях нет доступных вложений.");
                }catch(Exception failed){error=failed instanceof IOException?failed.getMessage():"Не удалось получить доступ к выбранному файлу.";}
                final String problem=error;
                AndroidUtilities.runOnUIThread(()->{
                    if(epoch!=generation||!touching||host.isFinished||host.getParentActivity()==null)return;
                    if(problem!=null){notice(problem);reset();return;}
                    View view=host.getFragmentView();if(view==null||!view.isAttachedToWindow()){reset();return;}
                    ClipData clip;
                    if(isText)clip=new ClipData("ghost-drag:"+payload.token,new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN},new ClipData.Item(payload.text));
                    else{
                        java.util.LinkedHashSet<String> types=new java.util.LinkedHashSet<>();types.add(ClipDescription.MIMETYPE_TEXT_URILIST);for(Item item:payload.items)types.add(item.mime);
                        clip=new ClipData("ghost-drag:"+payload.token,types.toArray(new String[0]),new ClipData.Item(payload.items.get(0).uri));
                        for(int i=1;i<payload.items.size();i++)clip.addItem(new ClipData.Item(payload.items.get(i).uri));
                    }
                    starting=true;active=payload;
                    AndroidUtilities.runOnUIThread(()->{if(active==payload){restoreSource(payload);active=null;}},600000);
                    host.ghostBeforeDrag();
                    boolean success=false;
                    try {success=view.startDragAndDrop(clip,new Shadow(view,payload,sourceCell),payload,View.DRAG_FLAG_GLOBAL|(isText?0:View.DRAG_FLAG_GLOBAL_URI_READ));}
                    catch(Exception ignored){}
                    starting=false;dragging=success;reset();
                    if(success){if(sourceCell!=null){payload.sourceView=new java.lang.ref.WeakReference<>(sourceCell);payload.sourceAlpha=sourceCell.getAlpha();sourceCell.animate().alpha(.65f).setDuration(100).start();}if(GhostDragSettings.get("haptic"))view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
                    else{if(active==payload)active=null;notice("Android не смог начать перетаскивание.");}
                });
            });
        }
    }
    private static void restoreSource(Payload payload){if(payload!=null&&payload.sourceView!=null){View view=payload.sourceView.get();payload.sourceView=null;if(view!=null)view.animate().alpha(payload.sourceAlpha).setDuration(140).start();}}

    private static final class Shadow extends View.DragShadowBuilder {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final Payload data;final int w=AndroidUtilities.dp(168),h=AndroidUtilities.dp(100);final Bitmap thumbnail;
        Shadow(View view,Payload data,ChatMessageCell cell){super(view);this.data=data;Bitmap thumb=null;
            try{Bitmap image=data.text!=null||cell==null?null:cell.getPhotoImage().getBitmap();if(image!=null&&!image.isRecycled())thumb=Bitmap.createScaledBitmap(image,AndroidUtilities.dp(80),AndroidUtilities.dp(80),true);}catch(Exception ignored){}
            thumbnail=thumb;
        }
        @Override public void onProvideShadowMetrics(Point size,Point touch){size.set(w,h);touch.set(w/2,h/2);}
        @Override public void onDrawShadow(Canvas c){
            float dp=AndroidUtilities.density;paint.setColor(0xdd263442);c.drawRoundRect(3*dp,6*dp,w-3*dp,h-6*dp,14*dp,14*dp,paint);
            if(data.items.size()>1){paint.setColor(0xff40566b);c.drawRoundRect(9*dp,2*dp,w-9*dp,h-10*dp,12*dp,12*dp,paint);}
            paint.setColor(Theme.getColor(Theme.key_dialogBackground));c.drawRoundRect(3*dp,6*dp,w-3*dp,h-6*dp,14*dp,14*dp,paint);
            int textX=(int)(12*dp);
            if(thumbnail==null&&data.text==null){Drawable icon=ApplicationLoader.applicationContext.getResources().getDrawable(org.telegram.messenger.R.drawable.media_doc_blue).mutate();icon.setBounds((int)(12*dp),(int)(28*dp),(int)(44*dp),(int)(66*dp));icon.draw(c);textX=(int)(52*dp);}
            if(thumbnail!=null){c.save();Path path=new Path();path.addRoundRect(9*dp,12*dp,81*dp,84*dp,9*dp,9*dp,Path.Direction.CW);c.clipPath(path);c.drawBitmap(thumbnail,null,new RectF(9*dp,12*dp,81*dp,84*dp),paint);c.restore();textX=(int)(88*dp);}
            paint.setColor(Theme.getColor(Theme.key_dialogTextBlack));paint.setTextSize(14*dp);
            String label=data.text!=null?data.text.replace('\n',' '):data.items.get(0).name;
            label=android.text.TextUtils.ellipsize(label,new android.text.TextPaint(paint),w-textX-12*dp,android.text.TextUtils.TruncateAt.END).toString();c.drawText(label,textX,44*dp,paint);
            paint.setTextSize(12*dp);paint.setColor(Theme.getColor(Theme.key_dialogTextLink));c.drawText(data.text!=null?"Текст":data.items.size()+" файл(ов)",textX,67*dp,paint);
        }
    }

    public static final class ChatReceiver {
        private final ChatActivity host;private final View root;private final EditText input;private boolean handled,hintFiles;private Runnable navigate;private Drawable previousForeground;private boolean highlighted;
        public ChatReceiver(ChatActivity host,View root,EditText input,View back){this.host=host;this.root=root;this.input=input;
            root.setOnDragListener((v,e)->event(v,e,false));
            input.setOnDragListener((v,e)->{if(!fileDescription(e.getClipDescription())&&!(e.getAction()==DragEvent.ACTION_DROP&&hasFileUris(e.getClipData())))return false;return event(v,e,true);});
            if(back!=null)back.setOnDragListener((v,e)->{
                if(e.getAction()==DragEvent.ACTION_DRAG_EXITED||e.getAction()==DragEvent.ACTION_DRAG_ENDED){cancelNavigation();return true;}
                if(!accepts(e.getClipDescription())){cancelNavigation();return false;}
                if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED){cancelNavigation();navigate=()->{navigate=null;host.ghostDragBack();};AndroidUtilities.runOnUIThread(navigate,600);}
                if(e.getAction()==DragEvent.ACTION_DRAG_EXITED||e.getAction()==DragEvent.ACTION_DRAG_ENDED)cancelNavigation();
                return e.getAction()!=DragEvent.ACTION_DROP;
            });
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){destroy();}});
        }
        private void cancelNavigation(){if(navigate!=null)AndroidUtilities.cancelRunOnUIThread(navigate);navigate=null;}
        private void highlight(boolean value){
            if(value==highlighted)return;highlighted=value;
            if(value){previousForeground=root.getForeground();root.setForeground(new Highlight(host,input,hintFiles));}
            else root.setForeground(previousForeground);
        }
        private boolean event(View target,DragEvent event,boolean onInput){
            int action=event.getAction();
            if(action==DragEvent.ACTION_DRAG_ENDED){highlight(false);cancelNavigation();Payload source=from(event);restoreSource(source);if(active==source)active=null;return true;}
            if(!accepts(event.getClipDescription())){highlight(false);cancelNavigation();return false;}
            if(action==DragEvent.ACTION_DRAG_STARTED){handled=false;Payload source=from(event);hintFiles=source!=null?!source.items.isEmpty():fileDescription(event.getClipDescription());return true;}
            if(action==DragEvent.ACTION_DRAG_ENTERED||action==DragEvent.ACTION_DRAG_LOCATION){highlight(true);return true;}
            if(action==DragEvent.ACTION_DRAG_EXITED){highlight(false);return true;}
            if(action!=DragEvent.ACTION_DROP)return true;
            highlight(false);cancelNavigation();if(handled)return false;
            Payload source=from(event);
            if(source!=null&&!GhostDragSettings.get("same_chat")&&source.sourceAccount==host.getCurrentAccount()&&source.sourceDialog==host.getDialogId()){notice("Отправка в тот же чат отключена.");return false;}
            int[] loc=new int[2];target.getLocationOnScreen(loc);float rawX=loc[0]+event.getX(),rawY=loc[1]+event.getY();
            int[] inputLoc=new int[2];input.getLocationOnScreen(inputLoc);
            int area=host.ghostDragDropArea(rawX,rawY);if(area==0)return false;
            boolean preview=onInput||area==2;
            boolean files=source!=null?!source.items.isEmpty():hasFileUris(event.getClipData());
            if(!files){
                if(!input.isShown()||!input.isEnabled()||rawX<inputLoc[0]||rawX>inputLoc[0]+input.getWidth()||rawY<inputLoc[1]||rawY>inputLoc[1]+input.getHeight()||!GhostDragSettings.get("text"))return false;
                ClipData data=event.getClipData();StringBuilder value=new StringBuilder();for(int i=0;i<data.getItemCount();i++){ClipData.Item item=data.getItemAt(i);CharSequence text=item.getText();if(text==null&&item.getUri()!=null&&("https".equals(item.getUri().getScheme())||"http".equals(item.getUri().getScheme())))text=item.getUri().toString();if(text!=null){if(value.length()>0)value.append('\n');value.append(text);}}
                if(value.length()==0)return false;handled=true;
                int offset=Math.max(0,Math.min(input.length(),input.getOffsetForPosition(rawX-inputLoc[0],rawY-inputLoc[1])));input.getText().insert(offset,value);input.setSelection(offset+value.length());input.requestFocus();return true;
            }
            if(!GhostDragSettings.get("files"))return false;
            if(!host.ghostDragCanReceive(source))return false;
            if(!GhostDragSettings.get("multiple")&&event.getClipData().getItemCount()>1){notice("Перетаскивание нескольких файлов отключено.");return false;}
            if(source!=null&&!source.claimed.compareAndSet(false,true))return false;
            handled=true;
            if(source!=null&&!source.items.isEmpty()){source.targetContext=host.ghostDragContext();host.ghostDragReceive(source,preview);return true;}
            receiveExternal(host,event,preview);return true;
        }
        public void destroy(){highlight(false);cancelNavigation();restoreSource(active);}
    }

    private static final class Highlight extends android.graphics.drawable.Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final ChatActivity host;final EditText input;final boolean files;
        Highlight(ChatActivity host,EditText input,boolean files){this.host=host;this.input=input;this.files=files;}
        private void area(Canvas c,Rect rect,String text){if(rect.isEmpty())return;RectF bounds=new RectF(rect);bounds.inset(AndroidUtilities.dp(5),AndroidUtilities.dp(3));paint.setColor(Theme.getColor(Theme.key_dialogTextLink));paint.setStyle(Paint.Style.FILL);paint.setAlpha(32);c.drawRoundRect(bounds,AndroidUtilities.dp(12),AndroidUtilities.dp(12),paint);paint.setAlpha(220);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(AndroidUtilities.dp(2));c.drawRoundRect(bounds,AndroidUtilities.dp(12),AndroidUtilities.dp(12),paint);paint.setStyle(Paint.Style.FILL);paint.setTextSize(AndroidUtilities.dp(15));paint.setTextAlign(Paint.Align.CENTER);c.drawText(text,bounds.centerX(),Math.max(bounds.top+AndroidUtilities.dp(20),bounds.bottom-AndroidUtilities.dp(12)),paint);}
        @Override public void draw(Canvas c){Rect[] areas=host.ghostDragTargetBounds();if(files&&host.ghostDragCanStart())area(c,areas[0],"Отправить вложения");if(input.isShown())area(c,areas[1],files?"Предпросмотр вложений":"Вставить текст");}
        public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private static void receiveExternal(ChatActivity host,DragEvent event,boolean preview){
        final Activity activity=host.getParentActivity();if(activity==null)return;
        final DragAndDropPermissions permission=activity.requestDragAndDropPermissions(event);
        final ArrayList<Uri> uris=new ArrayList<>();ClipData clip=event.getClipData();
        for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null&&"content".equals(uri.getScheme())&&!(ApplicationLoader.getApplicationId()+".provider").equals(uri.getAuthority()))uris.add(uri);}
        if(uris.isEmpty()){releasePermission(permission);notice("Нет доступных content:// вложений.");return;}
        AtomicBoolean cancelled=new AtomicBoolean();android.os.CancellationSignal signal=new android.os.CancellationSignal();java.util.concurrent.atomic.AtomicReference<InputStream> reading=new java.util.concurrent.atomic.AtomicReference<>();
        GhostBackgroundTasks.Task task=GhostBackgroundTasks.create("Перетаскивание вложений",()->{cancelled.set(true);new Thread(()->{signal.cancel();InputStream in=reading.get();if(in!=null)try{in.close();}catch(IOException ignored){}},"ghostDragCancel").start();});
        final int account=host.getCurrentAccount();final long dialog=host.getDialogId();
        final Object target=host.ghostDragContext();
        queue.postRunnable(()->{
            Payload payload=new Payload(account,Long.MIN_VALUE);payload.staged=true;payload.targetContext=target;File base=new File(AndroidUtilities.getCacheDir(),"ghost_drag/"+payload.token);payload.stagedRoot=base;String error=null;
            try{
                ContentResolver resolver=ApplicationLoader.applicationContext.getContentResolver();
                for(int i=0;i<uris.size();i++){
                    if(cancelled.get())throw new IOException("Отменено");Uri uri=uris.get(i);String name="file";long declared=-1;
                    try(android.database.Cursor cursor=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null,signal)){
                        if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0)name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))declared=cursor.getLong(s);}
                    }
                    if(declared>0&&!FileLoader.checkUploadFileSize(account,declared))throw new IOException("Файл превышает лимит этого аккаунта.");
                    name=GhostDragFiles.name(name);
                    File directory=new File(base,Integer.toString(i));if(!directory.mkdirs()&&!directory.isDirectory())throw new IOException("Не удалось создать временный файл.");File destination=new File(directory,name);
                    try(android.content.res.AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(uri,"r",signal);InputStream in=descriptor==null?null:descriptor.createInputStream();OutputStream out=new FileOutputStream(destination)){
                        reading.set(in);long expected=declared>=0?declared:descriptor==null?-1:descriptor.getLength();int index=i;
                        long limit=(UserConfig.getInstance(account).isPremium()?FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM:FileLoader.DEFAULT_MAX_FILE_SIZE)-1;
                        try{GhostDragFiles.copy(in,out,expected,limit,cancelled::get,copied->task.progress("Подготовка "+(index+1)+" / "+uris.size(),expected>0?(int)Math.min(99,copied*100/expected):-1));}
                        finally{reading.compareAndSet(in,null);}
                    }
                    String mime=resolver.getType(uri);payload.items.add(new Item(destination,FileProvider.getUriForFile(ApplicationLoader.applicationContext,ApplicationLoader.getApplicationId()+".provider",destination),name,mime==null?"application/octet-stream":mime,null));
                }
            }catch(Exception failed){error=failed instanceof IOException?failed.getMessage():"Не удалось прочитать вложение. Проверьте разрешение доступа.";}
            finally{releasePermission(permission);}
            final String problem=error;
            AndroidUtilities.runOnUIThread(()->{
                if(problem!=null||cancelled.get()||host.isFinished||host.getParentActivity()==null||host.getCurrentAccount()!=account||host.getDialogId()!=dialog){cleanup(payload,base);task.finish(problem==null?"Отменено":problem,null);if(problem!=null&&!cancelled.get())notice(problem);return;}
                task.finish("Вложения подготовлены",null);host.ghostDragReceive(payload,preview);
            });
        });
    }
    private static void releasePermission(DragAndDropPermissions permission){if(permission!=null)try{permission.release();}catch(RuntimeException ignored){}}
    private static void cleanup(Payload payload,File base){queue.postRunnable(()->deleteOwned(base));}
    public static void releaseStaged(Payload payload){if(payload.staged&&payload.stagedRoot!=null)cleanup(payload,payload.stagedRoot);}
    public static void validate(Payload payload,int account,Consumer<String> callback){
        queue.postRunnable(()->{
            String error=null;
            try{for(Item item:payload.items){
                if(!item.file.isFile()||!item.file.canRead()||item.file.length()==0||item.file.length()!=item.size)throw new IOException("Вложение больше недоступно или изменилось. Повторите перетаскивание после загрузки файла.");
                if(payload.sourceAccount!=account&&item.message!=null){
                    if(!FileLoader.checkUploadFileSize(account,item.file.length()))throw new IOException("Файл превышает лимит целевого аккаунта.");
                    org.telegram.tgnet.TLObject media=item.message.getDocument()!=null?item.message.getDocument():item.message.messageOwner.media.photo;
                    org.telegram.tgnet.SerializedData out=new org.telegram.tgnet.SerializedData();media.serializeToStream(out);org.telegram.tgnet.SerializedData in=new org.telegram.tgnet.SerializedData(out.toByteArray());
                    if(item.message.getDocument()!=null){item.uploadDocument=(org.telegram.tgnet.TLRPC.TL_document)org.telegram.tgnet.TLRPC.Document.TLdeserialize(in,in.readInt32(true),true);item.uploadDocument.id=0;item.uploadDocument.access_hash=0;item.uploadDocument.file_reference=new byte[0];item.uploadDocument.dc_id=0;}
                    else{item.uploadPhoto=(org.telegram.tgnet.TLRPC.TL_photo)org.telegram.tgnet.TLRPC.Photo.TLdeserialize(in,in.readInt32(true),true);item.uploadPhoto.id=0;item.uploadPhoto.access_hash=0;item.uploadPhoto.file_reference=new byte[0];}
                    out.cleanup();in.cleanup();
                }
            }}catch(Exception failure){error=failure instanceof IOException?failure.getMessage():"Не удалось подготовить вложение для целевого аккаунта.";}
            String problem=error;AndroidUtilities.runOnUIThread(()->callback.accept(problem));
        });
    }
    private static void deleteOwned(File file){if(file==null)return;File[] files=file.listFiles();if(files!=null)for(File child:files)deleteOwned(child);file.delete();}

    public static void installDialogs(DialogsActivity host,View root,LongConsumer open){
        class DialogReceiver implements View.OnDragListener,View.OnAttachStateChangeListener {long hover;Runnable pending;DialogCell lit;Drawable old;
            void clear(){if(pending!=null)AndroidUtilities.cancelRunOnUIThread(pending);pending=null;hover=0;if(lit!=null){lit.setForeground(old);lit=null;}}
            public void onViewAttachedToWindow(View view){}
            public void onViewDetachedFromWindow(View view){clear();}
            @Override public boolean onDrag(View v,DragEvent e){
                if(e.getAction()==DragEvent.ACTION_DRAG_ENDED||e.getAction()==DragEvent.ACTION_DRAG_EXITED){clear();return true;}
                if(!accepts(e.getClipDescription())){clear();return false;}
                if(e.getAction()==DragEvent.ACTION_DROP){clear();return false;}
                if(e.getAction()==DragEvent.ACTION_DRAG_LOCATION){
                    int[] xy=new int[2];root.getLocationOnScreen(xy);DialogCell cell=findDialog(root,xy[0]+e.getX(),xy[1]+e.getY());long id=cell==null?0:cell.getDialogId();
                    if(id!=hover){clear();if(id!=0){hover=id;lit=cell;old=cell.getForeground();cell.setForeground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10),androidx.core.graphics.ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_dialogTextLink),40)));pending=()->{long target=hover;clear();open.accept(target);};AndroidUtilities.runOnUIThread(pending,650);}}
                }
                return true;
            }
        }
        DialogReceiver receiver=new DialogReceiver();root.setOnDragListener(receiver);root.addOnAttachStateChangeListener(receiver);
    }
    private static DialogCell findDialog(View view,float x,float y){
        if(view.getVisibility()!=View.VISIBLE)return null;Rect bounds=new Rect();if(!view.getGlobalVisibleRect(bounds)||!bounds.contains((int)x,(int)y))return null;
        if(view instanceof DialogCell)return (DialogCell)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=group.getChildCount()-1;i>=0;i--){DialogCell cell=findDialog(group.getChildAt(i),x,y);if(cell!=null)return cell;}}return null;
    }
}
