package org.telegram.messenger;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;
public final class ApplicationLoader {
 public static final MemoryContext applicationContext=new MemoryContext();
 public static final class MemoryContext {
  private final Map<String,SharedPreferences> stores=new HashMap<>();
  public SharedPreferences getSharedPreferences(String name,int mode){return stores.computeIfAbsent(name,k->make());}
  private SharedPreferences make(){
   Map<String,Object> values=new HashMap<>();Map<String,Object> pending=new HashMap<>();
   SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
    switch(m.getName()){
     case "putBoolean":pending.put((String)a[0],a[1]);return p;
     case "remove":pending.put((String)a[0],null);return p;
     case "apply":case "commit":for(Map.Entry<String,Object> e:pending.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}pending.clear();return m.getName().equals("commit")?true:null;
     default:throw new UnsupportedOperationException(m.getName());
    }
   });
   return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
    switch(m.getName()){
     case "getBoolean":return values.getOrDefault((String)a[0],a[1]);
     case "edit":return editor;
     default:throw new UnsupportedOperationException(m.getName());
    }
   });
  }
 }
}
