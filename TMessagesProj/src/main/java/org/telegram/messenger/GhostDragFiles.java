package org.telegram.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded, cancellation-aware streaming; never buffers a complete attachment. */
public final class GhostDragFiles {
    private GhostDragFiles() {}
    public static String name(String value){
        String name=value==null?"file":value.replace('/','_').replace('\\','_').replace('\u0000','_');
        return name.equals(".")||name.equals("..")||name.trim().isEmpty()?"file":name;
    }
    public static long copy(InputStream in,OutputStream out,long declared,long limit,BooleanSupplier cancelled,LongConsumer progress)throws IOException {
        if(in==null)throw new IOException("Файл недоступен.");
        byte[] buffer=new byte[65536];long copied=0,last=0;int count;
        while(true){
            if(cancelled.getAsBoolean())throw new IOException("Отменено");
            count=in.read(buffer);if(count<0)break;
            if(count==0){int value=in.read();if(value<0)break;buffer[0]=(byte)value;count=1;}
            if(cancelled.getAsBoolean())throw new IOException("Отменено");
            if(copied>limit-count)throw new IOException("Файл превышает лимит аккаунта.");
            copied+=count;out.write(buffer,0,count);
            long now=System.nanoTime();if(now-last>200000000L){last=now;progress.accept(copied);}
        }
        if(cancelled.getAsBoolean())throw new IOException("Отменено");
        if(copied==0||declared>=0&&copied!=declared)throw new IOException("Файл пуст, повреждён или ещё не загружен.");
        progress.accept(copied);return copied;
    }
}
