package org.telegram.ui;

import android.content.Intent;
import android.view.Gravity;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BackupImageView;
import java.util.function.Consumer;

/** Original-quality file review; images use Telegram's asynchronous thumbnail loader. */
final class GhostDragPreview {
    static final class DropSheet extends BottomSheet {
        VideoView player;
        DropSheet(ChatActivity f){super(f.getParentActivity(),false,f.getResourceProvider());}
        void pausePlayer(){if(player!=null)player.pause();}
    }
    static void show(ChatActivity f,GhostDragDrop.Payload data,Consumer<Boolean> send){
        if(f.getParentActivity()==null){GhostDragDrop.releaseStaged(data);return;}
        LinearLayout content=new LinearLayout(f.getParentActivity());content.setOrientation(1);content.setPadding(AndroidUtilities.dp(16),0,AndroidUtilities.dp(16),AndroidUtilities.dp(16));
        FrameLayout viewer=new FrameLayout(f.getParentActivity());viewer.setVisibility(android.view.View.GONE);content.addView(viewer,new LinearLayout.LayoutParams(-1,Math.min(AndroidUtilities.dp(260),AndroidUtilities.displaySize.y/3)));
        TextView back=new TextView(f.getParentActivity());back.setText("‹ К списку вложений");back.setTextSize(15);back.setGravity(Gravity.CENTER);back.setTextColor(Theme.getColor(Theme.key_dialogTextLink,f.getResourceProvider()));back.setVisibility(android.view.View.GONE);content.addView(back,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(40)));
        final VideoView[] playing={null};final DropSheet sheet=new DropSheet(f);
        org.telegram.ui.Components.RecyclerListView rows=new org.telegram.ui.Components.RecyclerListView(f.getParentActivity());
        rows.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(f.getParentActivity()));
        rows.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>(){
            public int getItemCount(){return data.items.size();}
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent,int type){
                LinearLayout row=new LinearLayout(f.getParentActivity());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,AndroidUtilities.dp(4),0,AndroidUtilities.dp(4));row.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1,AndroidUtilities.dp(72)));
                BackupImageView thumb=new BackupImageView(f.getParentActivity());thumb.setRoundRadius(AndroidUtilities.dp(8));row.addView(thumb,new LinearLayout.LayoutParams(AndroidUtilities.dp(60),AndroidUtilities.dp(60)));
                TextView label=new TextView(f.getParentActivity());label.setTextSize(14);label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,f.getResourceProvider()));label.setMaxLines(3);label.setPadding(AndroidUtilities.dp(12),0,0,0);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(row){};
            }
            public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder,int position){
                GhostDragDrop.Item item=data.items.get(position);LinearLayout row=(LinearLayout)holder.itemView;BackupImageView thumb=(BackupImageView)row.getChildAt(0);TextView label=(TextView)row.getChildAt(1);thumb.clearImage();
                if(item.mime.startsWith("image/"))thumb.setImage(item.file.getAbsolutePath(),"64_64",(android.graphics.drawable.Drawable)null);else thumb.setImageResource(org.telegram.messenger.R.drawable.msg_gallery);
                label.setText(item.name+"\n"+AndroidUtilities.formatFileSize(item.size)+" "+item.mime);
                row.setOnClickListener(v->{
                    if(item.mime.startsWith("image/")||item.mime.startsWith("video/")||item.mime.startsWith("audio/")){
                        if(playing[0]!=null){playing[0].stopPlayback();playing[0]=null;}viewer.removeAllViews();viewer.setVisibility(android.view.View.VISIBLE);back.setVisibility(android.view.View.VISIBLE);rows.setVisibility(android.view.View.GONE);
                        if(item.mime.startsWith("image/")){BackupImageView image=new BackupImageView(f.getParentActivity());image.setAspectFit(true);image.setImage(item.file.getAbsolutePath(),"1024_1024",(android.graphics.drawable.Drawable)null);viewer.addView(image,new FrameLayout.LayoutParams(-1,-1));}
                        else{VideoView video=new VideoView(f.getParentActivity());playing[0]=video;sheet.player=video;viewer.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));android.widget.MediaController controls=new android.widget.MediaController(f.getParentActivity());controls.setAnchorView(video);video.setMediaController(controls);video.setVideoPath(item.file.getAbsolutePath());video.setOnErrorListener((p,what,extra)->{GhostDragDrop.notice("Этот файл нельзя воспроизвести в предпросмотре. Его можно отправить оригиналом.");return true;});video.start();}
                    }else try{Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(item.uri,item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);f.getParentActivity().startActivity(view);}catch(Exception ignored){GhostDragDrop.notice("Не удалось открыть предпросмотр файла.");}
                });
            }
        });
        content.addView(rows,new LinearLayout.LayoutParams(-1,Math.min(AndroidUtilities.dp(Math.min(5,data.items.size())*72),AndroidUtilities.displaySize.y/2)));
        back.setOnClickListener(v->{if(playing[0]!=null){playing[0].stopPlayback();playing[0]=null;}viewer.removeAllViews();viewer.setVisibility(android.view.View.GONE);back.setVisibility(android.view.View.GONE);rows.setVisibility(android.view.View.VISIBLE);});
        boolean mediaOnly=true;for(GhostDragDrop.Item item:data.items)mediaOnly&=item.mime.startsWith("image/")||item.mime.startsWith("video/");
        TextCheckCell original=new TextCheckCell(f.getParentActivity(),f.getResourceProvider());original.setTextAndCheck("Сохранить исходное качество",true,false);original.setOnClickListener(v->original.setChecked(!original.isChecked()));if(mediaOnly)content.addView(original);
        TextView button=new TextView(f.getParentActivity());button.setText("Отправить · "+data.items.size());button.setTextSize(16);button.setGravity(Gravity.CENTER);button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10),Theme.getColor(Theme.key_featuredStickers_addButton),Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));content.addView(button,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(48)));
        sheet.setTitle("Предпросмотр вложений");sheet.setCustomView(content);boolean[] dispatched={false};
        button.setOnClickListener(v->{if(dispatched[0]||!f.ghostDragCanSendMode(data,original.isChecked()))return;dispatched[0]=true;sheet.dismiss();send.accept(original.isChecked());});
        f.showDialog(sheet,d->{if(playing[0]!=null){playing[0].stopPlayback();playing[0]=null;}if(!dispatched[0])GhostDragDrop.releaseStaged(data);});
    }
}
