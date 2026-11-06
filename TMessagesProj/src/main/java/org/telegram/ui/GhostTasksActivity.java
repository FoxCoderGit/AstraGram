package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;

/** Native task list also works when notification permission is disabled. */
public final class GhostTasksActivity extends UniversalFragment {
    private final Runnable poll=new Runnable(){public void run(){if(listView!=null)listView.adapter.update(false);AndroidUtilities.runOnUIThread(this,1000);}};
    @Override protected CharSequence getTitle(){return "Фоновые задачи";}
    @Override public void onResume(){super.onResume();AndroidUtilities.cancelRunOnUIThread(poll);poll.run();}
    @Override public void onPause(){AndroidUtilities.cancelRunOnUIThread(poll);super.onPause();}
    @Override public void onFragmentDestroy(){AndroidUtilities.cancelRunOnUIThread(poll);super.onFragmentDestroy();}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        ArrayList<GhostBackgroundTasks.Task> tasks=GhostBackgroundTasks.list();
        if(tasks.isEmpty())items.add(UItem.asShadow("Здесь появятся создание скриншотов и обработка личных данных. Можно пользоваться приложением, пока идёт работа."));
        for(GhostBackgroundTasks.Task task:tasks){
            items.add(UItem.asButton(task.id,R.drawable.msg_customize,task.title));
            items.add(UItem.asShadow(task.stage+(task.running&&task.percent>=0?" · "+task.percent+"%":"")+(task.canOpen()?"\nНажмите, чтобы открыть результат.":task.finished?"\nНажмите, чтобы убрать из списка.":"\nНажмите, чтобы отменить.")));
        }
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        for(GhostBackgroundTasks.Task task:GhostBackgroundTasks.list())if(task.id==item.id){
            if(task.canOpen())task.open(getParentActivity());else if(task.finished)task.dismiss();
            else showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle("Отменить задачу?").setMessage(task.title).setPositiveButton("Отменить",(d,w)->task.cancel()).setNegativeButton("Назад",null).create());
            break;
        }
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
