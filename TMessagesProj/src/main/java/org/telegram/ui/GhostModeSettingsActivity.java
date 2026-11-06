package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.GhostMode;
import org.telegram.ui.Components.*;

/** Native Telegram cells, theme colors and a collapsible group, like AyuGram. */
public final class GhostModeSettingsActivity extends UniversalFragment {
    private boolean expanded=true;
    public GhostModeSettingsActivity(int account){setCurrentAccount(account);}
    @Override protected String getTitle(){return "Режим призрака";}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        items.add(UItem.asHeader("Режим призрака"));
        items.add(UItem.asExpandableSwitch(1,"Режим призрака",GhostMode.count(currentAccount)+"/5")
            .setChecked(GhostMode.masterOn(currentAccount)).setCollapsed(!expanded)
            .setClickCallback(v->{GhostMode.setEnabled(currentAccount,!GhostMode.masterOn(currentAccount));listView.adapter.update(true);}));
        if(expanded){
            String[] labels={"Не читать сообщения","Не читать истории","Не отправлять «онлайн»","Не отправлять «печатает»","Автоматический «офлайн»"};
            for(int i=0;i<5;i++)items.add(UItem.asRoundCheckbox(10+i,labels[i]).setPad(1)
                .setChecked(GhostMode.option(currentAccount,i)).setLocked(GhostMode.locked(currentAccount,i)));
        }
        items.add(UItem.asShadow("Удерживайте опцию, чтобы зафиксировать её значение. Общий переключатель не изменяет зафиксированные опции. Автоматический «офлайн» действует вместе с запретом онлайна."));
        items.add(UItem.asCheck(20,"Читать при действиях").setChecked(GhostMode.readActionsSelected(currentAccount)));
        items.add(UItem.asShadow("Отмечает сообщения прочитанными при отправке нового сообщения или реакции. Это может раскрыть активность. При включении отключает отложку."));
        items.add(UItem.asCheck(21,"Использовать отложку").setChecked(GhostMode.scheduleSelected(currentAccount)));
        items.add(UItem.asShadow("Отправляет сообщения через очередь Telegram с задержкой от 12 секунд, для вложений — дольше. Работает, когда включены все 5 опций. При включении отключает чтение при действиях. На слабой сети скрытие онлайна не гарантируется. Боты, Избранное и секретные чаты не используют этот механизм."));
        items.add(UItem.asShadow("Звонки, реакции и другие активные сеансы могут раскрывать активность независимо от этих настроек."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        if(item.id==1)expanded=!expanded;
        else if(item.id>=10&&item.id<15){int option=item.id-10;GhostMode.setOption(currentAccount,option,!GhostMode.option(currentAccount,option));}
        else if(item.id==20)GhostMode.setReadActions(currentAccount,!GhostMode.readActionsSelected(currentAccount));
        else if(item.id==21)GhostMode.setSchedule(currentAccount,!GhostMode.scheduleSelected(currentAccount));
        else return;
        listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){
        if(item.id<10||item.id>=15)return false;
        GhostMode.toggleLock(currentAccount,item.id-10);
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        listView.adapter.update(true);return true;
    }
}
