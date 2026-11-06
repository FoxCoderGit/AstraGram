package org.telegram.ui;

import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.*;
import org.telegram.ui.Components.*;

public final class GhostAccountsOrderActivity extends UniversalFragment {
    @Override protected CharSequence getTitle(){return "Порядок аккаунтов";}
    @Override public View createView(Context context){
        View view=super.createView(context);
        listView.listenReorder((section,items)->{
            ArrayList<Integer> order=new ArrayList<>();for(UItem item:items)order.add(item.id-1);GhostAccountOrder.save(order);
        });
        listView.allowReorder(true);return view;
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        items.add(UItem.asShadow("Удерживайте аккаунт и перетащите его на нужное место. Порядок сохраняется для старого и нового меню."));
        adapter.reorderSectionStart();
        for(int account:GhostAccountOrder.accounts()){
            UItem item=UItem.asProfileCell(UserConfig.getInstance(account).getCurrentUser());item.id=account+1;items.add(item);
        }
        adapter.reorderSectionEnd();
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
