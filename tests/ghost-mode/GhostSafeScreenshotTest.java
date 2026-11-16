import java.lang.reflect.Method;

/** Runs against the installed APK with synthetic identities only. */
public final class GhostSafeScreenshotTest {
    public static void main(String[] args) throws Exception {
        Method redact=Class.forName("org.telegram.ui.Components.GhostSafeScreenshot")
                .getMethod("redact",String.class,String[].class);
        String[] names={"Alice Smith","Alice","Smith","@alice","Bob Jones","Bob","Jones","@bob"};
        String text="Alice Smith @alice replied to Bob Jones @bob. +79991234567 https://example.org/a";
        String value=(String)redact.invoke(null,text,names);
        if(value.contains("Alice")||value.contains("Smith")||value.contains("Bob")||value.contains("Jones")
                ||value.contains("@alice")||value.contains("@bob")||value.contains("79991234567")||value.contains("example.org"))
            throw new AssertionError("Identity remained in safe text");
        if(!value.contains("я")||!value.contains("собеседник"))throw new AssertionError("Missing aliases");
        if(!"".equals(redact.invoke(null,null,names)))throw new AssertionError("Null text");
        Class<?> safe=Class.forName("org.telegram.ui.Components.GhostSafeScreenshot");
        Class<?> messageType=Class.forName("org.telegram.messenger.MessageObject");
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Object unsafe=singleton.get(null);
        Method allocate=unsafeType.getMethod("allocateInstance",Class.class);
        Object original=allocate.invoke(unsafe,messageType),cached=allocate.invoke(unsafe,messageType);
        Object owner=Class.forName("org.telegram.tgnet.TLRPC$TL_message").getConstructor().newInstance();
        owner.getClass().getField("dialog_id").setLong(owner,456L);
        messageType.getField("messageOwner").set(original,owner);
        messageType.getField("stableId").setInt(original,101);
        java.lang.reflect.Field scopesField=safe.getDeclaredField("scopes"),copiesField=safe.getDeclaredField("copies");
        scopesField.setAccessible(true);copiesField.setAccessible(true);
        java.util.Map scopes=(java.util.Map)scopesField.get(null),copies=(java.util.Map)copiesField.get(null);
        try{
            scopes.put("0:456",names);copies.put(original,cached);
            Method display=safe.getMethod("display",messageType);
            if(display.invoke(null,original)!=cached||messageType.getField("stableId").getInt(cached)!=101)
                throw new AssertionError("Safe render lost RecyclerView stable ID");
            messageType.getField("stableId").setInt(original,202);display.invoke(null,original);
            if(messageType.getField("stableId").getInt(cached)!=202)throw new AssertionError("Stale stable ID after rebind");
        }finally{scopes.remove("0:456");copies.remove(original);}
        System.out.println("Safe screenshot RecyclerView stable IDs: rebind regression passed.");
        System.out.println("Safe screenshot redaction: synthetic identity/link/phone checks passed.");
    }
}
