package org.telegram.ui.Stories.recorder;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import org.telegram.ui.recyclerview.LinearSmoothScrollerCustom;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.DeviceMusicLibrary;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.TextWatcherImpl;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.ChatAttachAlertAudioLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.utils.glass.GlassEngine;
import org.telegram.utils.glass.positions.GlassPositionsArray;
import org.telegram.utils.glass.positions.GlassPositionsMerger;

import java.io.File;
import java.util.ArrayList;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.BitwiseUtils;

public class SelectAudioAlert extends BottomSheetWithRecyclerListView implements NotificationCenter.NotificationCenterDelegate, DownloadController.FileDownloadProgressListener, FactorAnimator.Target {

    private static final int ANIMATOR_ID_FADE_VISIBLE = 0;
    private final BoolAnimator animatorFadeVisible = new BoolAnimator(ANIMATOR_ID_FADE_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);

    private final int tag;
    private boolean local;
    private final SelectAudioAlert parentAlert;
    private final ArrayList<MessageObject> localAudio = new ArrayList<>();
    private final ArrayList<MessageObject> sharedAudio = new ArrayList<>();
    private final ArrayList<MessageObject> globalAudio = new ArrayList<>();
    private final MessagesController.SavedMusicList savedMusicList;
    private final Utilities.Callback<MessageObject> onAudioSelected;
    private MessageObject downloadingMessageObject;
    private boolean withoutSavedMusic;

    private boolean ignoreScroll;

    private final FrameLayout frameLayout;
    private final View fadeView;
    private final FragmentSearchField searchField;

    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;

    private final @Nullable GlassSourceRenderNode iBlur3SourceGlass;
    private final @NonNull GlassSourceColor iBlur3SourceColor;
    private final @NonNull BlurredBackgroundDrawableViewFactory iBlur3FactoryLiquidGlass;
    private final @NonNull BlurredBackgroundDrawableViewFactory iBlur3FactoryFade;

    private final IBlur3Capture iBlur3Capture;

    public SelectAudioAlert(Context context, Utilities.Callback<MessageObject> onAudioSelected, Theme.ResourcesProvider resourcesProvider) {
        this(context, false, null, onAudioSelected, resourcesProvider);
    }

    public SelectAudioAlert(Context context, boolean local, SelectAudioAlert parentAlert, Utilities.Callback<MessageObject> onAudioSelected, Theme.ResourcesProvider resourcesProvider) {
        super(context, null, true, false, false, ActionBarType.SLIDING, resourcesProvider);

        topPadding = 0.35f;
        fixNavigationBar();
        setSlidingActionBar();
        headerPaddingTop = dp(4);
        headerPaddingBottom = dp(-20);

        this.local = local;
        tag = DownloadController.getInstance(currentAccount).generateObserverTag();
        this.parentAlert = parentAlert;
        this.onAudioSelected = onAudioSelected;

        iBlur3SourceColor = glassEngine.createThemedColorSource(() -> getThemedColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SharedConfig.chatBlurEnabled()) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            glassEngine.addNoiseSuppressor(scrollableViewNoiseSuppressor);

            iBlur3SourceGlass = new GlassSourceRenderNode(iBlur3SourceColor);
            iBlur3SourceGlass.setUnderSource(iBlur3SourceColor);
            iBlur3SourceGlass.setScrollableNoiseSuppressor(scrollableViewNoiseSuppressor,DownscaleScrollableNoiseSuppressor.DRAW_GLASS);

            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            iBlur3FactoryLiquidGlass.setLiquidGlassEffectAllowed(true);
            iBlur3FactoryLiquidGlass.setOutset(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? dp(8) : dp(48));
            iBlur3FactoryLiquidGlass.setGlassEngine(glassEngine);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlass = null;
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
        iBlur3FactoryFade = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);

        iBlur3Capture = (canvas, position) -> {
            Blur3Utils.captureRelativeParent(recyclerListView, canvas, position, recyclerListView, getContainerView(), 0xFF);
        };

        fadeView = new ChatAttachAlert.SearchFadeView(context, Theme.key_windowBackgroundGray, resourcesProvider);
        fadeView.setVisibility(View.INVISIBLE);
        frameLayout = new FrameLayout(context);

        searchField = new FragmentSearchField(context, resourcesProvider);
        searchField.editText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    ignoreScroll = true;
                    scrollToSearchTop();
                }
            }
        });
        searchField.setSectionBackground();
        searchField.setPadding(dp(4), dp(4), dp(4), dp(4));
        searchField.editText.addTextChangedListener(new TextWatcherImpl() {
            @Override
            public void afterTextChanged(Editable s) {
                final String lastQuery = query;
                query = s.toString();
                if (!SelectAudioAlert.this.local) {
                    if (!TextUtils.equals(lastLoadingSharedAudioQuery, query == null ? "" : query)) {
                        cancelLoadingSharedAudio();
                        willLoadSharedAudio = query != null && query.length() > 0;
                    }
                    if (!TextUtils.equals(lastLoadingGlobalAudioQuery, query == null ? "" : query)) {
                        cancelLoadingGlobalAudio();
                        willLoadGlobalAudio = query != null && query.length() > 3 && !TextUtils.isEmpty(MessagesController.getInstance(currentAccount).config.musicSearchUsername.get());
                    }

//                    if (!TextUtils.isEmpty(lastQuery) && TextUtils.isEmpty(query)) {
//                        loadSharedAudio();
//                        loadGlobalAudio();
//                    } else {
                        loadSharedAudioDelayed();
                        loadGlobalAudioDelayed();
//                    }
                }
                adapter.update(true);
            }
        });
        searchField.editText.setHint(LocaleController.getString(R.string.Search));
        frameLayout.addView(fadeView, LayoutHelper.createFrameMatchParent());
        frameLayout.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 0, 8, 0, 4));
        searchField.setupBlurredBackground(iBlur3FactoryLiquidGlass.create(searchField, BlurredBackgroundProviderImpl.topPanel(resourcesProvider)));
        frameLayout.setPadding(backgroundPaddingLeft + dp(8), 0, backgroundPaddingLeft + dp(8), 0);
        containerView.addView(frameLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL));

        setBackgroundColorKey(Theme.key_windowBackgroundGray); // BottomSheet recolors it on theme changes
        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        recyclerListView.setSections();
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDurations(350);
        recyclerListView.setItemAnimator(itemAnimator);

        if (!local) {
            savedMusicList = new MessagesController.SavedMusicList(currentAccount, UserConfig.getInstance(currentAccount).getClientUserId());
            savedMusicList.load();
            loadSharedAudio();

            loadGlobalAudio();
        } else {
            savedMusicList = null;
        }

        recyclerListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE && ignoreScroll) {
                    ignoreScroll = false;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateSearchY();
                if (recyclerListView.scrollingByUser && !ignoreScroll) {
                    AndroidUtilities.hideKeyboard(containerView);
                }
            }
        });
        recyclerListView.setOnItemClickListener((view, position) -> {
            if (view instanceof SharedAudioCell) {
                final SharedAudioCell cell = ((SharedAudioCell) view);
                final MessageObject messageObject = cell.getMessage();
                if (messageObject == null) return;

                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
                if (downloadingMessageObject != null) {
                    FileLoader.getInstance(currentAccount).cancelLoadFile(downloadingMessageObject.getDocument());
                    downloadingMessageObject = null;
                }

                if (!messageObject.attachPathExists && !messageObject.mediaExists) {
                    final String fileName = messageObject.getFileName();
                    if (TextUtils.isEmpty(fileName)) {
                        return;
                    }
                    downloadingMessageObject = messageObject;
                    DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, messageObject, this);
                    FileLoader.getInstance(currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_NORMAL, 0);
                } else {
                    done(messageObject);
                }
            } else {
                final UItem item = adapter.getItem(position - 1);
                if (item != null && item.id == 1) {
                    new SelectAudioAlert(getContext(), true, this, onAudioSelected, resourcesProvider).show();
                } else if (item != null && item.id == 2) {
                    savedMusicList.load();
                } else if (item != null && item.id == 3) {
                    loadSharedAudio();
                } else if (item != null && item.id == 4) {
                    loadGlobalAudio();
                }
            }
        });
        glassEngine.addScrolledView(recyclerListView);

        glassEngine.setRoot(containerView);
        glassEngine.setGlassInvalidationListener(this::invalidateBlurCaptures);
        glassEngine.setPositionsMerger(new GlassPositionsMerger(Float.POSITIVE_INFINITY, dp(48)));
    }

    @Override
    protected void preDrawInternal(Canvas canvas, View parent) {
        updateSearchY();
        super.preDrawInternal(canvas, parent);
    }

    private void scrollToSearchTop() {
        final LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerListView.getLayoutManager();
        final LinearSmoothScrollerCustom linearSmoothScroller = new LinearSmoothScrollerCustom(getContext(), LinearSmoothScrollerCustom.POSITION_TOP);
        linearSmoothScroller.setTargetPosition(1);
        linearSmoothScroller.setOffset(AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() - dp(1));
        layoutManager.startSmoothScroll(linearSmoothScroller);
    }

    private void updateSearchY() {
        float top = AndroidUtilities.displaySize.y;
        for (int i = 0; i < recyclerListView.getChildCount(); ++i) {
            final View child = recyclerListView.getChildAt(i);
            final int position = recyclerListView.getChildAdapterPosition(child);
            if (position >= 1 && child.getY() < top) {
                top = child.getY();
            }
        }
        frameLayout.setTranslationY(Math.max(AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), top));
        animatorFadeVisible.setValue(top <= AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), true);
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_FADE_VISIBLE) {
            fadeView.setAlpha(factor);
            fadeView.setVisibility(factor > 0 ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void done(MessageObject messageObject) {
        onAudioSelected.run(messageObject);
        if (parentAlert != null) {
            parentAlert.dismiss();
        }
        dismiss();
    }

    public SelectAudioAlert withoutSavedMusic() {
        this.withoutSavedMusic = true;
        this.local = false;
        adapter.update(true);
        return this;
    }

    @Override
    public void dismiss() {
        super.dismiss();

        if (playingAudio != null && MediaController.getInstance().isPlayingMessage(playingAudio)) {
            MediaController.getInstance().cleanupPlayer(true, true);
        }
        playingAudio = null;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.StoryMusicTitle2);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.musicListLoaded) {
            adapter.update(true);
        }
    }

    private UniversalAdapter adapter;
    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    private MessageObject playingAudio;
    private boolean needPlayMessage(MessageObject messageObject) {
        playingAudio = messageObject;
        ArrayList<MessageObject> arrayList = new ArrayList<>();
        arrayList.add(messageObject);
        return MediaController.getInstance().setPlaylist(arrayList, messageObject, 0);
    }

    private String query;
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        adapter.itemsOffset = 1;
        int height = dp(64);
        items.add(UItem.asSpace(dp(64)));
        if (local || withoutSavedMusic) {
            height += addSection(true, items, getString(R.string.AudioSearchLocal), localAudio, false, false, -1);
        }
        if (!local) {
            if (TextUtils.isEmpty(query) && !withoutSavedMusic) {
                adapter.whiteSectionStart();
                items.add(UItem.asButton(1, R.drawable.msg2_folder, getString(R.string.StoryMusicSelectFromFiles)).accent());
                adapter.whiteSectionEnd();
                height += dp(50);
            }
            if (!withoutSavedMusic && savedMusicList != null) {
                height += addSection(true, items, getString(R.string.AudioSearchProfile), savedMusicList.list, savedMusicList.loading, !savedMusicList.endReached, 2);
            }
            height += addSection(false, items, getString(R.string.AudioSearchChats), sharedAudio, willLoadSharedAudio || loadingSharedAudio, sharedAudioHasMore, 3);
            height += addSection(false, items, getString(R.string.AudioSearchGlobal), globalAudio, willLoadGlobalAudio || loadingGlobalAudio, globalAudioHasMore, 4);
        }
        if (items.size() <= (!local && TextUtils.isEmpty(query) && !withoutSavedMusic ? 2 : 1)) {
            if (TextUtils.isEmpty(query)) {
                items.add(ChatAttachAlertAudioLayout.EmptyView.Factory.as(getString(R.string.NoAudioFound), getString(R.string.NoAudioFilesInfo)));
            } else {
                items.add(ChatAttachAlertAudioLayout.EmptyView.Factory.as(getString(R.string.NoAudioFound), AndroidUtilities.replaceTags(formatString(query.length() >= 3 ? R.string.NoAudioFoundInfo2 : R.string.NoAudioFoundInfo, query))));
            }
        }
//        if (!TextUtils.isEmpty(query) || isKeyboardVisible()) {
            items.add(UItem.asShadow(null));
            height += dp(12);
            items.add(UItem.asSpace(Math.max(0, AndroidUtilities.displaySize.y - height - AndroidUtilities.statusBarHeight - ActionBar.getCurrentActionBarHeight() + dp(24))));
//        }
    }

    private int addSection(boolean filter, ArrayList<UItem> items, String name, ArrayList<MessageObject> messageObjects, boolean loading, boolean showMore, int showMoreId) {
        if (messageObjects == null || messageObjects.isEmpty() && !loading) {
            return 0;
        }
        final ArrayList<MessageObject> filtered = new ArrayList<>();
        final String lquery = query == null ? null : query.toLowerCase();
        final String tquery = AndroidUtilities.translitSafe(lquery);
        for (final MessageObject messageObject : messageObjects) {
            if (!filter) {
                messageObject.setQuery(query);
                filtered.add(messageObject);
            } else if (TextUtils.isEmpty(lquery) || messageObjects == sharedAudio) {
                messageObject.setQuery(null);
                filtered.add(messageObject);
            } else {
                final String title = messageObject.getMusicTitle();
                final String author = messageObject.getMusicAuthor();
                if (matches(lquery, tquery, title) || matches(lquery, tquery, author)) {
                    messageObject.setQuery(query);
                    filtered.add(messageObject);
                }
            }
        }
        if (filtered.isEmpty() && !loading) {
            return 0;
        }
        int height = 0;
        if (!items.isEmpty() && items.size() > 1) {
            items.add(UItem.asShadow(null));
            height += dp(12);
        }
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(name));
        for (final MessageObject messageObject : filtered) {
            items.add(SharedAudioCell.Factory.as(messageObject, this::needPlayMessage));
            height += dp(56);
        }
        if (loading) {
            items.add(UItem.asFlicker(FlickerLoadingView.AUDIO_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.AUDIO_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.AUDIO_TYPE));
            height += 3 * dp(56);
        }
        if (showMore && !loading) {
            items.add(UItem.asButton(showMoreId, R.drawable.arrow_more, getString(R.string.ShowMore)).accent());
            height += dp(50);
        }
        adapter.whiteSectionEnd();
        return height;
    }

    private boolean matches(String lquery, String tquery, String name) {
        if (name == null) return false;
        final String lname = name.toLowerCase();
        if (lname.startsWith(lquery) || lname.contains(" " + lquery))
            return true;
        final String tname = AndroidUtilities.translitSafe(lname);
        if (tname.startsWith(tquery) || tname.contains(" " + tquery))
            return true;
        return false;
    }

    private int nextSearchRate;
    private boolean sharedAudioHasMore;
    private boolean loadingSharedAudio;
    private boolean willLoadSharedAudio;
    private String lastLoadingSharedAudioQuery;
    private int loadingSharedAudioRequestId = -1;
    private void cancelLoadingSharedAudio() {
        if (loadingSharedAudioRequestId >= 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(loadingSharedAudioRequestId, true);
        }
        loadingSharedAudioRequestId = -1;
        nextSearchRate = 0;
        sharedAudio.clear();
        loadingSharedAudio = false;
        willLoadSharedAudio = false;
    }
    private void loadSharedAudioDelayed() {
        AndroidUtilities.cancelRunOnUIThread(loadSharedAudioRunnable);
        AndroidUtilities.runOnUIThread(loadSharedAudioRunnable, 400);
    }
    private Runnable loadSharedAudioRunnable = this::loadSharedAudio;
    private void loadSharedAudio() {
        if (local) return;

        if (!TextUtils.equals(lastLoadingSharedAudioQuery, query == null ? "" : query)) {
            cancelLoadingSharedAudio();
        }

        if (loadingSharedAudio) return;
        if (!sharedAudio.isEmpty() && !sharedAudioHasMore) return;

        loadingSharedAudio = true;

        final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.filter = new TLRPC.TL_inputMessagesFilterMusic();
        req.q = lastLoadingSharedAudioQuery = query == null ? "" : query;
        req.limit = 20;
        if (sharedAudio != null && sharedAudio.size() > 0) {
            final MessageObject lastMessage = sharedAudio.get(sharedAudio.size() - 1);
            req.offset_id = lastMessage.getId();
            req.offset_rate = nextSearchRate;
            long id = MessageObject.getPeerId(lastMessage.messageOwner.peer_id);
            req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(id);
        } else {
            req.offset_rate = 0;
            req.offset_id = 0;
            req.offset_peer = new TLRPC.TL_inputPeerEmpty();
        }
        loadingSharedAudioRequestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            willLoadSharedAudio = false;
            loadingSharedAudio = false;
            if (res instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages r = (TLRPC.messages_Messages) res;
                MessagesController.getInstance(currentAccount).putUsers(r.users, false);
                MessagesController.getInstance(currentAccount).putChats(r.chats, false);

                for (final TLRPC.Message message : r.messages) {
                    final MessageObject messageObject = new MessageObject(currentAccount, message, false, true);
                    sharedAudio.add(messageObject);
                }

                sharedAudioHasMore = r instanceof TLRPC.TL_messages_messagesSlice && sharedAudio.size() < r.count;
                nextSearchRate = r.next_rate;
            } else {
                sharedAudioHasMore = false;
                nextSearchRate = 0;
            }
            adapter.update(true);
        }));

        adapter.update(true);
    }

    private String globalAudioOffset;
    private boolean globalAudioHasMore;
    private boolean loadingGlobalAudio;
    private boolean willLoadGlobalAudio;
    private TLRPC.User globalAudioBot;
    private boolean resolvingGlobalAudioBot;
    private boolean failedToResolveGlobalAudioBot;
    private int loadingGlobalAudioRequestId = -1;
    private String lastLoadingGlobalAudioQuery;
    private int globalAudioId = -2000000000;
    private void cancelLoadingGlobalAudio() {
        if (loadingGlobalAudioRequestId >= 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(loadingGlobalAudioRequestId, true);
        }
        loadingGlobalAudioRequestId = -1;
        globalAudioOffset = "";
        globalAudioHasMore = false;
        globalAudio.clear();
        loadingGlobalAudio = false;
        willLoadGlobalAudio = false;
    }
    private void loadGlobalAudioDelayed() {
        AndroidUtilities.cancelRunOnUIThread(loadGlobalAudioRunnable);
        AndroidUtilities.runOnUIThread(loadGlobalAudioRunnable, 400);
    }
    private Runnable loadGlobalAudioRunnable = this::loadGlobalAudio;
    private void loadGlobalAudio() {
        final String botUsername = MessagesController.getInstance(currentAccount).config.musicSearchUsername.get();
        if (TextUtils.isEmpty(botUsername)) return;

        if (!TextUtils.equals(lastLoadingGlobalAudioQuery, query == null ? "" : query)) {
            cancelLoadingGlobalAudio();
        }

        if (loadingGlobalAudio) return;
        if (TextUtils.isEmpty(query) || query.length() < 3) return;
        if (!globalAudio.isEmpty() && !globalAudioHasMore) return;

        if (globalAudioBot == null) {
            globalAudioBot = MessagesController.getInstance(currentAccount).getUser(botUsername);
        }
        if (globalAudioBot == null) {
            if (resolvingGlobalAudioBot || failedToResolveGlobalAudioBot) return;
            resolvingGlobalAudioBot = true;
            MessagesController.getInstance(currentAccount).getUserNameResolver().resolve(botUsername, botId -> {
                resolvingGlobalAudioBot = false;
                globalAudioBot = botId == null ? null : MessagesController.getInstance(currentAccount).getUser(botId);
                failedToResolveGlobalAudioBot = globalAudioBot == null;
                if (globalAudioBot != null) {
                    loadGlobalAudio();
                }
            });
            return;
        }

        loadingGlobalAudio = true;
        final TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
        final TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.bot = MessagesController.getInstance(currentAccount).getInputUser(globalAudioBot);
        req.peer = MessagesController.getInputPeer(self);
        req.offset = globalAudio.isEmpty() || globalAudioOffset == null ? "" : globalAudioOffset;
        req.query = lastLoadingGlobalAudioQuery = query == null ? "" : query;
        loadingGlobalAudioRequestId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            loadingGlobalAudio = false;
            willLoadGlobalAudio = false;
            if (res != null) {
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                for (TLRPC.BotInlineResult r : res.results) {
                    if (r instanceof TLRPC.TL_botInlineMediaResult) {
                        final TLRPC.TL_botInlineMediaResult rMedia = (TLRPC.TL_botInlineMediaResult) r;
                        if (rMedia.document != null) {
                            final TLRPC.TL_message message = new TLRPC.TL_message();
                            message.out = true;
                            message.id = globalAudioId--;
                            message.peer_id = new TLRPC.TL_peerUser();
                            message.from_id = new TLRPC.TL_peerUser();
                            message.peer_id.user_id = message.from_id.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
                            message.date = (int) (System.currentTimeMillis() / 1000);
                            message.message = "";
                            message.media = new TLRPC.TL_messageMediaDocument();
                            message.media.flags |= 3;
                            message.media.document = rMedia.document;
                            message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
                            globalAudio.add(new MessageObject(currentAccount, message, false, true));
                        }
                    }
                }
                globalAudioOffset = res.next_offset;
                globalAudioHasMore = !globalAudio.isEmpty() && !TextUtils.isEmpty(globalAudioOffset);

                adapter.update(true);
            } else {
                adapter.update(true);
            }
        });

        adapter.update(true);
    }

    private boolean loadingLocalAudio;
    private final DeviceMusicLibrary.Listener deviceMusicListener = this::onDeviceMusic;
    private final java.util.HashMap<DeviceMusicLibrary.Track, MessageObject> deviceEntries = new java.util.HashMap<>();
    private int nextDeviceMessageId = -2000000000;

    private void onDeviceMusic(java.util.List<DeviceMusicLibrary.Track> tracks, boolean loading) {
        loadingLocalAudio = loading;
        deviceEntries.keySet().retainAll(new java.util.HashSet<>(tracks));
        localAudio.clear();
        for (DeviceMusicLibrary.Track track : tracks) {
            MessageObject message = deviceEntries.get(track);
            if (message == null) {
                message = track.createEntry(currentAccount, nextDeviceMessageId--).resolveMessage();
                deviceEntries.put(track, message);
            }
            localAudio.add(message);
        }
        adapter.update(true);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (local) DeviceMusicLibrary.getInstance().subscribe(deviceMusicListener);
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.musicListLoaded);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        DeviceMusicLibrary.getInstance().unsubscribe(deviceMusicListener);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicListLoaded);
    }

    @Override
    public void onFailedDownload(String fileName, boolean canceled) {

    }

    @Override
    public void onSuccessDownload(String fileName) {
        if (downloadingMessageObject != null && TextUtils.equals(downloadingMessageObject.getFileName(), fileName)) {
            done(downloadingMessageObject);
        }
    }

    @Override
    public void onProgressDownload(String fileName, long downloadSize, long totalSize) {

    }

    @Override
    public void onProgressUpload(String fileName, long downloadSize, long totalSize, boolean isEncrypted) {

    }

    @Override
    public int getObserverTag() {
        return tag;
    }

    private void invalidateBlurCaptures(int flags) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null) {
            return;
        }

        if (BitwiseUtils.hasFlag(flags, GlassEngine.FLAG_INVALIDATED_POSITIONS)) {
            GlassPositionsArray glassPositionsArray = glassEngine.getAllVisibleDrawablesWithDisplayListPositionsMerged();
            scrollableViewNoiseSuppressor.setupRenderNodes(glassPositionsArray);
        }

        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, containerView.getWidth(), containerView.getHeight());
    }

    @Override
    public void onThemeColorsChanged() {
        try {
            super.onThemeColorsChanged();
            if (iBlur3SourceColor != null) {
                iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
