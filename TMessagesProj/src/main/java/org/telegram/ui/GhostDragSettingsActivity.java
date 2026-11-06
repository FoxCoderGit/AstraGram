package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.GhostDragSettings;
import org.telegram.ui.Components.*;

public final class GhostDragSettingsActivity extends UniversalFragment {
    @Override protected String getTitle(){return "Перетаскивание (Drag & Drop)";}
    @Override public boolean onFragmentCreate(){super.onFragmentCreate();return true;}
    @Override public View createView(android.content.Context context){View view=super.createView(context);actionBar.setTitle("Перетаскивание (Drag & Drop)");return view;}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        String[] labels={"Включить Drag & Drop","Файлы и медиа","Выделенный текст","Несколько файлов","Вибрация в начале","Отправка в тот же чат"};
        for(int i=0;i<labels.length;i++)items.add(UItem.asCheck(i+1,labels[i]).setChecked(GhostDragSettings.get(GhostDragSettings.KEYS[i])));
        items.add(UItem.asShadow("Удерживайте вложение около секунды, затем двигайте палец. Для выбранной группы достаточно 350 мс. Наведите на кнопку назад, затем на нужный чат. Файл должен быть полностью загружен. Внешний обмен зависит от поддержки Android Drag & Drop другим приложением."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){if(item.id>=1&&item.id<=6){String key=GhostDragSettings.KEYS[item.id-1];GhostDragSettings.prefs().edit().putBoolean(key,!GhostDragSettings.get(key)).apply();listView.adapter.update(true);}}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
