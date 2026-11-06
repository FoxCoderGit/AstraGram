package org.ghostgram.messenger.plugins;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mvel2.MVEL;
import org.mvel2.optimizers.OptimizerFactory;
import org.telegram.messenger.*;

public final class PluginMenuBridge {
    private static final ConcurrentHashMap<String,Serializable> expressions=new ConcurrentHashMap<>();
    static { OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE); }
    public static boolean matches(String condition,Map<String,Object> context) {
        if(condition==null||condition.isEmpty()||context==null)return true;
        try { Object result=MVEL.executeExpression(expressions.computeIfAbsent(condition,MVEL::compileExpression),context);return result instanceof Boolean&&(Boolean)result; }
        catch(Throwable error){return false;}
    }
    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            PluginBridge.refreshBoundMenus();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginsUpdated);
            for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++)if(UserConfig.getInstance(account).isClientActivated())
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.pluginMenuItemsUpdated);
        });
    }
}
