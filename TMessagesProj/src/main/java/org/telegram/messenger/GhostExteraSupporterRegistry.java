package org.telegram.messenger;

import org.json.*;
import java.util.*;

/** Only USER/SUPPORTER membership from the official public profiles endpoint. */
public final class GhostExteraSupporterRegistry {
    public final Map<Long,Long> documents;
    private GhostExteraSupporterRegistry(Map<Long,Long> values){documents=Collections.unmodifiableMap(new HashMap<>(values));}
    private static long positive(Object value)throws JSONException{
        String text=String.valueOf(value);
        if(!text.matches("[1-9][0-9]{0,18}"))throw new JSONException("Invalid profile identifier");
        try{return Long.parseLong(text);}catch(NumberFormatException error){throw new JSONException("Invalid profile identifier");}
    }
    public static GhostExteraSupporterRegistry parse(String text)throws JSONException{
        if(text==null||text.length()>4*1024*1024)throw new JSONException("Profiles response too large");
        JSONArray profiles=new JSONArray(text);
        if(profiles.length()>50000)throw new JSONException("Too many profiles");
        Map<Long,Long> result=new HashMap<>();Set<Long> seen=new HashSet<>();
        for(int i=0;i<profiles.length();i++){
            JSONObject profile=profiles.getJSONObject(i);
            if(!"USER".equals(profile.optString("type")))continue;
            long id=positive(profile.opt("id"));
            if(!seen.add(id))throw new JSONException("Duplicate profile");
            if(!"SUPPORTER".equals(profile.optString("status")))continue;
            JSONObject badge=profile.optJSONObject("badge");
            if(badge==null)continue;
            result.put(id,positive(badge.opt("documentId")));
        }
        return new GhostExteraSupporterRegistry(result);
    }
}
