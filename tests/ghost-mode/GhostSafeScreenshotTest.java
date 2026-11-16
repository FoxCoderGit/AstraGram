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
        System.out.println("Safe screenshot redaction: synthetic identity/link/phone checks passed.");
    }
}
