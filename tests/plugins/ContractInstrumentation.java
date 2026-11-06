package org.ghostgram.contracttests;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class ContractInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(!Python.isStarted())Python.start(new AndroidPlatform(getTargetContext()));
            Python python=Python.getInstance();PyObject builtins=python.getModule("builtins"),scope=builtins.callAttr("dict");
            scope.put("context",getTargetContext());scope.put("fixture",new Fixture());
            scope.put("fixture_method",Fixture.class.getDeclaredMethod("compute",int.class));
            scope.put("fixture_text_method",Fixture.class.getDeclaredMethod("text"));
            scope.put("fixture_constructor",Fixture.Child.class.getDeclaredConstructor(int.class));
            String script;
            try(InputStream input=getContext().getAssets().open("contract_device.py");ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);script=output.toString("UTF-8");
            }
            builtins.callAttr("exec",script,scope);
            result.putString("stream","PASS: "+scope.get("report").toString()+"\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            java.io.StringWriter text=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(text));
            result.putString("stream","FAIL: "+text+"\n");finish(Activity.RESULT_CANCELED,result);
        }
    }
    public static class Parent { private int secret=7; }
    public static final class Fixture extends Parent {
        public static int compute(int value){return value*2;}
        public int call(int value){return compute(value);}
        public static String text(){return "original";}
        public String textCall(){return text();}
        public String getText(){return "fixture";}
        public Child child(int value){return new Child(value);}
        public static final class Child { public int value;public Child(int value){this.value=value;} }
    }
}
