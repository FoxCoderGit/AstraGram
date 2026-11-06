package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Presentation order only: account slots, sessions and databases never move. */
public final class GhostAccountOrder {
    private static final String KEY="ghost_account_order";
    public static ArrayList<Integer> accounts(){
        ArrayList<Integer> result=new ArrayList<>();
        for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++)if(UserConfig.getInstance(i).isClientActivated())result.add(i);
        sort(result);return result;
    }
    public static void sort(List<Integer> accounts){
        HashMap<Long,Integer> ranks=new HashMap<>();int rank=0;
        for(String id:MessagesController.getGlobalMainSettings().getString(KEY,"").split(","))try{ranks.putIfAbsent(Long.parseLong(id),rank++);}catch(NumberFormatException ignored){}
        accounts.sort((a,b)->{
            int ra=ranks.getOrDefault(UserConfig.getInstance(a).getClientUserId(),Integer.MAX_VALUE),rb=ranks.getOrDefault(UserConfig.getInstance(b).getClientUserId(),Integer.MAX_VALUE);
            int c=Integer.compare(ra,rb);if(c!=0)return c;
            c=Long.compare(UserConfig.getInstance(a).loginTime,UserConfig.getInstance(b).loginTime);return c!=0?c:Integer.compare(a,b);
        });
    }
    public static void save(List<Integer> accounts){
        StringBuilder value=new StringBuilder();
        for(int account:accounts)if(UserConfig.isValidAccount(account)&&UserConfig.getInstance(account).isClientActivated()){
            if(value.length()>0)value.append(',');value.append(UserConfig.getInstance(account).getClientUserId());
        }
        MessagesController.getGlobalMainSettings().edit().putString(KEY,value.toString()).apply();
    }
    private GhostAccountOrder(){}
}
