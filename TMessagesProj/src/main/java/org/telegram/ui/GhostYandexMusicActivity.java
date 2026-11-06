package org.telegram.ui;

import android.content.Context;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GhostExperimental;
import org.telegram.messenger.GhostYandexMusicClient;
import org.telegram.messenger.GhostYandexMusicProtocol;
import org.telegram.messenger.GhostYandexMusicRelay;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.UUID;

/** Experimental, account-authorized Yandex Music picker. Sending always requires an explicit tap. */
public final class GhostYandexMusicActivity extends BaseFragment {
    public interface SendTrack { void send(MessageObject track); }
    private static final int YELLOW=0xffffcc00;
    private static final int AUTH_REQUEST=22893;
    private final long telegramOwner;
    private final SendTrack sendTrack;
    private final GhostYandexMusicClient client;
    private final ArrayList<GhostYandexMusicClient.Track> tracks=new ArrayList<>();
    private FrameLayout frame;
    private LinearLayout main,login,accountRow,footer;
    private TextView accountName,status,selection,send,loginButton,tokenButton;
    private TextView searchTab,likedTab;
    private TextView routing;
    private EditTextBoldCursor search;
    private ProgressBar progress;
    private RecyclerView list;
    private TrackAdapter adapter;
    private String authState,query="",previewId;
    private int page,queryVersion;
    private boolean more,destroyed,likesMode;
    private GhostYandexMusicClient.Track selected;
    private GhostYandexMusicClient.Operation searchOperation,downloadOperation,authOperation,relayOperation;
    private Runnable pendingSearch;
    private MediaPlayer player;
    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focus=change->{if(change<0)AndroidUtilities.runOnUIThread(this::stopPreview);};

    public GhostYandexMusicActivity(int account,SendTrack sendTrack){
        super();setCurrentAccount(account);this.sendTrack=sendTrack;
        telegramOwner=UserConfig.getInstance(account).getClientUserId();client=new GhostYandexMusicClient(account);
    }
    @Override public boolean onFragmentCreate(){return org.telegram.messenger.GhostOptionalFeatures.enabled(org.telegram.messenger.GhostOptionalFeatures.MUSIC)&&telegramOwner>0&&super.onFragmentCreate();}
    private boolean alive(){return !destroyed&&org.telegram.messenger.GhostOptionalFeatures.enabled(org.telegram.messenger.GhostOptionalFeatures.MUSIC)&&telegramOwner==getUserConfig().getClientUserId();}
    private int color(int key){return Theme.getColor(key);}
    private TextView text(Context context,String value,int size,int key){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(color(key));view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView button(Context context,String label){
        TextView view=text(context,label,15,Theme.key_windowBackgroundWhiteBlackText);view.setTextColor(Color.BLACK);view.setTypeface(AndroidUtilities.bold());view.setGravity(Gravity.CENTER);
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),YELLOW,0xffe8b900));return view;
    }
    @Override public View createView(Context context){
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);actionBar.setTitle("Я.музыка");
        actionBar.setAllowOverlayTitle(true);actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        frame=new FrameLayout(context);frame.setBackgroundColor(color(Theme.key_windowBackgroundWhite));fragmentView=frame;
        main=new LinearLayout(context);main.setOrientation(LinearLayout.VERTICAL);frame.addView(main,LayoutHelper.createFrame(-1,-1));
        accountRow=new LinearLayout(context);accountRow.setGravity(Gravity.CENTER_VERTICAL);accountRow.setPadding(AndroidUtilities.dp(18),AndroidUtilities.dp(12),AndroidUtilities.dp(18),AndroidUtilities.dp(8));
        TextView mark=text(context,"",18,Theme.key_windowBackgroundWhiteBlackText);mark.setBackground(new Icon());accountRow.addView(mark,LayoutHelper.createLinear(36,36));
        accountName=text(context,"",13,Theme.key_windowBackgroundWhiteGrayText);accountRow.addView(accountName,LayoutHelper.createLinear(0,44,1f,12,0,8,0));
        TextView refresh=text(context,"↻",24,Theme.key_windowBackgroundWhiteBlueText);refresh.setGravity(Gravity.CENTER);refresh.setContentDescription("Обновить список");accountRow.addView(refresh,LayoutHelper.createLinear(40,44));
        refresh.setOnClickListener(v->{client.invalidateLikes();startSearch();});
        TextView logout=text(context,"Отключить",13,Theme.key_windowBackgroundWhiteBlueText);accountRow.addView(logout,LayoutHelper.createLinear(-2,44));
        logout.setOnClickListener(v->new AlertDialog.Builder(context).setTitle("Отключить Яндекс Музыку?")
                .setMessage("Вы выйдете из Яндекс Музыки для всех аккаунтов Telegram на этом устройстве. Для поиска и отправки треков понадобится войти снова.").setNegativeButton("Отмена",null).setPositiveButton("Отключить",(dialog,which)->logout()).show());
        main.addView(accountRow,LayoutHelper.createLinear(-1,-2));
        LinearLayout tabs=new LinearLayout(context);tabs.setPadding(AndroidUtilities.dp(16),AndroidUtilities.dp(4),AndroidUtilities.dp(16),AndroidUtilities.dp(8));
        searchTab=button(context,"Поиск");likedTab=button(context,"♥  Мне нравится");
        tabs.addView(searchTab,LayoutHelper.createLinear(0,42,1f,0,0,5,0));tabs.addView(likedTab,LayoutHelper.createLinear(0,42,1f,5,0,0,0));main.addView(tabs,LayoutHelper.createLinear(-1,54));
        searchTab.setOnClickListener(v->switchMode(false));likedTab.setOnClickListener(v->switchMode(true));updateTabs();
        FrameLayout searchBox=new FrameLayout(context);searchBox.setPadding(AndroidUtilities.dp(16),AndroidUtilities.dp(4),AndroidUtilities.dp(16),AndroidUtilities.dp(10));
        search=new EditTextBoldCursor(context);search.setSingleLine(true);search.setTextSize(16);search.setHint("Трек или исполнитель");search.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        search.setHintTextColor(color(Theme.key_windowBackgroundWhiteGrayText));search.setCursorColor(color(Theme.key_windowBackgroundWhiteBlackText));search.setPadding(AndroidUtilities.dp(14),0,AndroidUtilities.dp(14),0);
        search.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14),color(Theme.key_windowBackgroundGray)));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        searchBox.addView(search,LayoutHelper.createFrame(-1,48));main.addView(searchBox,LayoutHelper.createLinear(-1,62));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){queueSearch();}public void afterTextChanged(Editable e){}});
        search.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH){AndroidUtilities.hideKeyboard(search);startSearch();return true;}return false;});
        progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.INVISIBLE);main.addView(progress,LayoutHelper.createLinear(-1,3));
        routing=text(context,"",12,Theme.key_windowBackgroundWhiteBlueText);routing.setGravity(Gravity.CENTER);routing.setOnClickListener(v->showRouting());main.addView(routing,LayoutHelper.createLinear(-1,32));updateRouting();
        FrameLayout content=new FrameLayout(context);main.addView(content,LayoutHelper.createLinear(-1,0,1f));
        list=new RecyclerView(context);list.setLayoutManager(new LinearLayoutManager(context));adapter=new TrackAdapter();list.setAdapter(adapter);content.addView(list,LayoutHelper.createFrame(-1,-1));
        status=text(context,"",15,Theme.key_windowBackgroundWhiteGrayText);status.setGravity(Gravity.CENTER);status.setPadding(AndroidUtilities.dp(28),0,AndroidUtilities.dp(28),0);status.setOnClickListener(v->startSearch());content.addView(status,LayoutHelper.createFrame(-1,-1));
        login=new LinearLayout(context);login.setOrientation(LinearLayout.VERTICAL);login.setGravity(Gravity.CENTER);login.setPadding(AndroidUtilities.dp(30),0,AndroidUtilities.dp(30),AndroidUtilities.dp(24));
        login.setClickable(true);login.setBackgroundColor(color(Theme.key_windowBackgroundWhite));content.addView(login,LayoutHelper.createFrame(-1,-1));
        View logo=new View(context);logo.setBackground(new Icon());login.addView(logo,LayoutHelper.createLinear(76,76));
        TextView title=text(context,"Ваша музыка — в чате",23,Theme.key_windowBackgroundWhiteBlackText);title.setTypeface(AndroidUtilities.bold());title.setGravity(Gravity.CENTER);login.addView(title,LayoutHelper.createLinear(-1,-2,0,24,0,10));
        TextView description=text(context,"Войдите в свой Яндекс. Вход сохранится для всех аккаунтов Telegram на этом устройстве.",15,Theme.key_windowBackgroundWhiteGrayText);description.setGravity(Gravity.CENTER);login.addView(description,LayoutHelper.createLinear(-1,-2,0,0,0,24));
        loginButton=button(context,"Войти в Яндекс");login.addView(loginButton,LayoutHelper.createLinear(-1,50));loginButton.setOnClickListener(v->openAuth());
        tokenButton=text(context,"Ввести токен Яндекс Музыки",14,Theme.key_windowBackgroundWhiteBlueText);tokenButton.setGravity(Gravity.CENTER);tokenButton.setPadding(0,AndroidUtilities.dp(12),0,AndroidUtilities.dp(4));login.addView(tokenButton,LayoutHelper.createLinear(-1,42));tokenButton.setOnClickListener(v->showTokenLogin());
        TextView note=text(context,"Экспериментальная интеграция\nДоступность музыки зависит от вашего аккаунта.",12,Theme.key_windowBackgroundWhiteGrayText);note.setGravity(Gravity.CENTER);login.addView(note,LayoutHelper.createLinear(-1,-2,0,18,0,0));
        footer=new LinearLayout(context);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(AndroidUtilities.dp(16),AndroidUtilities.dp(10),AndroidUtilities.dp(16),AndroidUtilities.dp(14));
        selection=text(context,"",13,Theme.key_windowBackgroundWhiteBlackText);selection.setMaxLines(2);selection.setEllipsize(TextUtils.TruncateAt.END);footer.addView(selection,LayoutHelper.createLinear(0,50,1f,0,0,12,0));
        send=button(context,"Отправить");footer.addView(send,LayoutHelper.createLinear(122,48));send.setOnClickListener(v->{if(downloadOperation!=null)cancelDownload();else if(selected!=null)download(selected,false);});
        main.addView(footer,LayoutHelper.createLinear(-1,-2));updateSession();return fragmentView;
    }
    private void updateSession(){if(main==null)return;boolean connected=client.authorized();login.setVisibility(connected?View.GONE:View.VISIBLE);accountRow.setVisibility(connected?View.VISIBLE:View.GONE);search.setEnabled(connected);footer.setVisibility(connected&&selected!=null?View.VISIBLE:View.GONE);
        searchTab.setEnabled(connected);likedTab.setEnabled(connected);accountName.setText("Подключено: "+client.session.name());if(connected&&tracks.isEmpty())setStatus(likesMode?"Откройте понравившиеся треки":"Найдите музыку по названию\nили откройте «Мне нравится»");}
    private void updateTabs(){
        if(searchTab==null)return;
        TextView[] tabs={searchTab,likedTab};for(int i=0;i<tabs.length;i++){boolean selected=likesMode==(i==1);tabs[i].setTextColor(selected?Color.BLACK:color(Theme.key_windowBackgroundWhiteBlackText));
            tabs[i].setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14),selected?YELLOW:color(Theme.key_windowBackgroundGray)));tabs[i].setSelected(selected);}
        if(search!=null)search.setHint(likesMode?"В понравившихся: трек или исполнитель":"Трек или исполнитель");
    }
    private void switchMode(boolean liked){if(likesMode==liked)return;likesMode=liked;cancelDownload();stopPreview();selected=null;footer.setVisibility(View.GONE);search.setText("");updateTabs();startSearch();}
    private void setStatus(String value){if(status!=null){status.setText(value);status.setVisibility(value==null||value.isEmpty()?View.GONE:View.VISIBLE);}}
    private void queueSearch(){if(pendingSearch!=null)AndroidUtilities.cancelRunOnUIThread(pendingSearch);pendingSearch=this::startSearch;AndroidUtilities.runOnUIThread(pendingSearch,400);}
    private void startSearch(){
        if(!alive()||!client.authorized())return;if(pendingSearch!=null)AndroidUtilities.cancelRunOnUIThread(pendingSearch);if(searchOperation!=null)searchOperation.cancel();
        query=search.getText().toString().trim();page=0;more=false;tracks.clear();adapter.notifyDataSetChanged();int version=++queryVersion;
        if(query.isEmpty()&&!likesMode){setStatus("Найдите музыку по названию\nили откройте «Мне нравится»");return;}
        setStatus(likesMode?(query.isEmpty()?"Загружаем понравившиеся…":"Ищем среди понравившихся…"):"Ищем в Яндекс Музыке…");loadPage(version,0);
    }
    private void loadPage(int version,int requested){
        if(searchOperation!=null)searchOperation.cancel();if(downloadOperation==null){progress.setIndeterminate(true);progress.setVisibility(View.VISIBLE);}
        if(likesMode)searchOperation=client.liked(query,requested,(found,error)->applyPage(version,found==null?null:found.tracks,found!=null&&found.more,found==null?requested:found.nextOffset,error));
        else searchOperation=client.search(query,requested,(found,error)->applyPage(version,found,found!=null&&!found.isEmpty(),requested+1,error));
    }
    private void applyPage(int version,ArrayList<GhostYandexMusicClient.Track> found,boolean hasMore,int next,String error){
        if(!alive()||version!=queryVersion)return;searchOperation=null;if(downloadOperation==null)progress.setVisibility(View.INVISIBLE);
        if(error!=null){updateSession();setStatus(error);return;}page=next;more=hasMore;tracks.addAll(found);adapter.notifyDataSetChanged();
        setStatus(tracks.isEmpty()?(likesMode?(query.isEmpty()?"Пока нет доступных понравившихся треков.\nДобавьте их в Яндекс Музыке и нажмите ↻.":"Среди понравившихся ничего не найдено."):"Ничего не найдено. Попробуйте другой запрос."):null);
    }
    private void select(GhostYandexMusicClient.Track track){if(downloadOperation!=null)return;selected=track;selection.setText(track.title+"\n"+track.artist);footer.setVisibility(View.VISIBLE);adapter.notifyDataSetChanged();}
    private void download(GhostYandexMusicClient.Track track,boolean preview){
        if(!alive()||downloadOperation!=null)return;if(!track.available){error("Трек недоступен для вашего аккаунта");return;}
        select(track);AndroidUtilities.hideKeyboard(search);progress.setIndeterminate(true);progress.setVisibility(View.VISIBLE);selection.setText("Готовим трек…\n"+track.title);send.setText("Отмена");
        downloadOperation=client.download(track,(bytes,total)->{if(!alive()||downloadOperation==null)return;if(total>0){progress.setIndeterminate(false);progress.setProgress((int)Math.min(100,bytes*100/total));selection.setText("Загрузка "+(bytes*100/total)+"%\n"+track.title);}},(audio,problem)->{
            if(!alive())return;downloadOperation=null;progress.setVisibility(View.INVISIBLE);send.setText("Отправить");selection.setText(track.title+"\n"+track.artist);
            if(problem!=null){updateSession();error(problem);return;}
            if(preview){play(audio);return;}
            stopPreview();MessageObject attachment=client.attachment(audio);finishFragment();AndroidUtilities.runOnUIThread(()->{if(org.telegram.messenger.GhostOptionalFeatures.enabled(org.telegram.messenger.GhostOptionalFeatures.MUSIC)&&telegramOwner==getUserConfig().getClientUserId())sendTrack.send(attachment);},220);
        });
    }
    private void cancelDownload(){if(downloadOperation!=null)downloadOperation.cancel();downloadOperation=null;progress.setVisibility(View.INVISIBLE);send.setText("Отправить");if(selected!=null)selection.setText(selected.title+"\n"+selected.artist);}
    private void play(GhostYandexMusicClient.Audio audio){
        stopPreview();try{audioManager=(AudioManager)getParentActivity().getSystemService(Context.AUDIO_SERVICE);audioManager.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(audio.file.getAbsolutePath());player.setOnPreparedListener(value->{if(alive()&&player==value)value.start();});player.setOnCompletionListener(value->stopPreview());player.setOnErrorListener((value,what,extra)->{stopPreview();error("Не удалось воспроизвести этот файл");return true;});
            previewId=audio.track.id;player.prepareAsync();adapter.notifyDataSetChanged();
        }catch(Exception ignored){stopPreview();error("Не удалось включить прослушивание");}
    }
    private void stopPreview(){if(player!=null){player.release();player=null;}if(audioManager!=null)audioManager.abandonAudioFocus(focus);previewId=null;if(adapter!=null)adapter.notifyDataSetChanged();}
    private void error(String value){if(!destroyed&&getParentActivity()!=null)new AlertDialog.Builder(getParentActivity()).setTitle("Я.музыка").setMessage(value).setPositiveButton("Понятно",null).show();}
    private void logout(){cancelDownload();stopPreview();if(searchOperation!=null)searchOperation.cancel();queryVersion++;client.session.clear();client.clearCaches();tracks.clear();selected=null;search.setText("");adapter.notifyDataSetChanged();updateSession();}
    private void updateRouting(){if(routing!=null)routing.setText(GhostYandexMusicRelay.enabled()?"Подключение: RU-сервер · изменить":"Подключение: напрямую · изменить");}
    private void showRouting(){
        if(!alive()||relayOperation!=null)return;
        new AlertDialog.Builder(getParentActivity()).setTitle("Подключение Я.музыки")
                .setItems(new CharSequence[]{"Через RU-сервер (автоматически)","Напрямую без VPN"},(dialog,index)->{
                    GhostYandexMusicRelay.setEnabled(index==0);routingChanged();
                }).setNegativeButton("Отмена",null).show();
    }
    private void routingChanged(){cancelDownload();if(searchOperation!=null)searchOperation.cancel();client.invalidateLikes();updateRouting();startSearch();}
    private void openAuth(){
        if(!alive()||authState!=null)return;stopPreview();authState=UUID.randomUUID().toString();
        try{startActivityForResult(new Intent(getParentActivity(),GhostYandexMusicAuthActivity.class).putExtra(GhostYandexMusicAuthActivity.STATE,authState).putExtra(GhostYandexMusicAuthActivity.USE_RELAY,GhostYandexMusicRelay.enabled()),AUTH_REQUEST);}
        catch(Exception failure){authState=null;error("Не удалось открыть вход в Яндекс");}
    }
    private void showTokenLogin(){
        if(!alive()||authOperation!=null||getParentActivity()==null)return;
        final Context context=getParentActivity();
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),0);
        TextView hint=text(context,"Введите личный OAuth-токен Яндекс Музыки. Он проверяется через API Яндекса и хранится только в зашифрованном хранилище этого устройства.",14,Theme.key_windowBackgroundWhiteGrayText);content.addView(hint,LayoutHelper.createLinear(-1,-2,0,0,0,12));
        EditTextBoldCursor field=new EditTextBoldCursor(context);field.setSingleLine(true);field.setTextSize(16);field.setHint("OAuth-токен");field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);field.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));field.setHintTextColor(color(Theme.key_windowBackgroundWhiteHintText));field.setCursorColor(color(Theme.key_windowBackgroundWhiteBlackText));content.addView(field,LayoutHelper.createLinear(-1,48));
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle("Токен Яндекс Музыки").setView(content).setNegativeButton("Отмена",null).setPositiveButton("Подключить",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String token=field.getText().toString().trim().replaceFirst("(?i)^OAuth\\s+","");
            if(token.isEmpty()){field.setError("Введите токен");return;}
            AndroidUtilities.hideKeyboard(field);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);loginButton.setEnabled(false);tokenButton.setEnabled(false);progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);
            authOperation=client.signIn(token,(name,problem)->{if(!alive())return;authOperation=null;progress.setVisibility(View.INVISIBLE);loginButton.setEnabled(true);tokenButton.setEnabled(true);updateSession();if(problem!=null){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);field.setError("Не удалось подтвердить токен");error(problem);}else{dialog.dismiss();startSearch();}});
        }));
        showDialog(dialog);
    }
    @Override public void onActivityResultFragment(int requestCode,int resultCode,Intent data){
        if(requestCode!=AUTH_REQUEST){super.onActivityResultFragment(requestCode,resultCode,data);return;}
        String expected=authState;authState=null;if(!alive())return;
        if(resultCode!=Activity.RESULT_OK){if(data!=null&&data.hasExtra(GhostYandexMusicAuthActivity.ERROR))error(data.getStringExtra(GhostYandexMusicAuthActivity.ERROR));return;}
        String token=data==null?null:GhostYandexMusicProtocol.callbackToken(data.getStringExtra(GhostYandexMusicAuthActivity.CALLBACK),expected);
        if(token==null){error("Не удалось подтвердить вход. Попробуйте снова.");return;}
        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);loginButton.setEnabled(false);tokenButton.setEnabled(false);loginButton.setText("Проверяем вход…");
        authOperation=client.signIn(token,(name,problem)->{if(!alive())return;authOperation=null;progress.setVisibility(View.INVISIBLE);loginButton.setEnabled(true);tokenButton.setEnabled(true);loginButton.setText("Войти в Яндекс");updateSession();if(problem!=null)error(problem);else startSearch();});
    }
    @Override public void onPause(){super.onPause();stopPreview();}
    @Override public void onResume(){super.onResume();if(!alive())finishFragment();}
    @Override public void onFragmentDestroy(){destroyed=true;if(pendingSearch!=null)AndroidUtilities.cancelRunOnUIThread(pendingSearch);if(searchOperation!=null)searchOperation.cancel();if(authOperation!=null)authOperation.cancel();if(downloadOperation!=null)downloadOperation.cancel();if(relayOperation!=null)relayOperation.cancel();stopPreview();authState=null;super.onFragmentDestroy();}

    private final class TrackAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        @Override public int getItemCount(){return tracks.size()+(more?1:0);}
        @Override public int getItemViewType(int position){return position<tracks.size()?0:1;}
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
            if(type==1){TextView more=button(parent.getContext(),"Ещё треки");more.setLayoutParams(new RecyclerView.LayoutParams(-1,AndroidUtilities.dp(52)));return new Holder(more);}
            return new Holder(new TrackRow(parent.getContext()));
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){
            if(position>=tracks.size()){holder.itemView.setOnClickListener(v->{if(searchOperation==null)loadPage(queryVersion,page);});return;}
            GhostYandexMusicClient.Track track=tracks.get(position);TrackRow row=(TrackRow)holder.itemView;row.bindCover(track.cover);row.title.setText(track.title);row.artist.setText(track.artist);
            row.duration.setText(String.format(java.util.Locale.US,"%d:%02d",track.durationMs/60000,track.durationMs/1000%60));row.play.setText(track.id.equals(previewId)?"Ⅱ":"▶");row.check.setText(selected!=null&&selected.id.equals(track.id)?"✓":"");
            row.setAlpha(track.available?1:.5f);row.setOnClickListener(v->select(track));row.play.setOnClickListener(v->{if(track.id.equals(previewId))stopPreview();else download(track,true);});
        }
        @Override public void onViewRecycled(RecyclerView.ViewHolder holder){if(holder.itemView instanceof TrackRow)((TrackRow)holder.itemView).cancelCover();super.onViewRecycled(holder);}
    }
    private static final class Holder extends RecyclerView.ViewHolder{Holder(View view){super(view);}}
    private final class TrackRow extends FrameLayout {
        final BackupImageView cover;final TextView title,artist,duration,play,check;
        private String coverKey;private GhostYandexMusicClient.Operation coverOperation;
        void cancelCover(){if(coverOperation!=null)coverOperation.cancel();coverOperation=null;coverKey=null;}
        void bindCover(String url){
            if(url!=null&&url.equals(coverKey))return;cancelCover();cover.setImageDrawable(new Icon());coverKey=url;
            if(url!=null)coverOperation=client.cover(url,(bitmap,error)->{if(alive()&&url.equals(coverKey)){coverOperation=null;if(bitmap!=null)cover.setImageBitmap(bitmap);}});
        }
        @Override protected void onDetachedFromWindow(){cancelCover();super.onDetachedFromWindow();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();int index=list.getChildAdapterPosition(this);if(index>=0&&index<tracks.size())bindCover(tracks.get(index).cover);}
        TrackRow(Context context){super(context);setLayoutParams(new RecyclerView.LayoutParams(-1,AndroidUtilities.dp(82)));setBackground(Theme.getSelectorDrawable(false));
            cover=new BackupImageView(context);cover.setRoundRadius(AndroidUtilities.dp(10));addView(cover,LayoutHelper.createFrame(56,56,Gravity.LEFT|Gravity.TOP,16,13,0,0));
            title=text(context,"",15,Theme.key_windowBackgroundWhiteBlackText);title.setTypeface(AndroidUtilities.bold());title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);addView(title,LayoutHelper.createFrame(-1,25,Gravity.TOP,84,15,68,0));
            artist=text(context,"",13,Theme.key_windowBackgroundWhiteGrayText);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);addView(artist,LayoutHelper.createFrame(-1,23,Gravity.TOP,84,41,68,0));
            duration=text(context,"",11,Theme.key_windowBackgroundWhiteGrayText);duration.setGravity(Gravity.RIGHT);addView(duration,LayoutHelper.createFrame(44,20,Gravity.RIGHT|Gravity.TOP,0,47,18,0));
            check=text(context,"",22,Theme.key_windowBackgroundWhiteBlackText);check.setGravity(Gravity.CENTER);addView(check,LayoutHelper.createFrame(42,32,Gravity.RIGHT|Gravity.TOP,0,10,16,0));
            play=text(context,"▶",16,Theme.key_windowBackgroundWhiteBlackText);play.setTextColor(Color.BLACK);play.setGravity(Gravity.CENTER);play.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),YELLOW));play.setContentDescription("Прослушать трек");addView(play,LayoutHelper.createFrame(28,28,Gravity.LEFT|Gravity.TOP,44,41,0,0));
        }
    }
    public static final class Icon extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas){float x=getBounds().exactCenterX(),y=getBounds().exactCenterY(),size=Math.min(getBounds().width(),getBounds().height());paint.setColor(YELLOW);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,size*.48f,paint);paint.setColor(Color.BLACK);paint.setStrokeWidth(size*.095f);paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x-size*.19f,y-size*.08f,x-size*.19f,y+size*.18f,paint);canvas.drawLine(x,y-size*.24f,x,y+size*.24f,paint);canvas.drawLine(x+size*.19f,y-size*.18f,x+size*.19f,y+size*.08f,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
