package org.telegram.messenger;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Validates every bounded native field before JNI receives imported authorization. */
public final class GhostAuthSnapshot {
    private static int count(ByteBuffer b,int max)throws IOException{int n=b.getInt();if(n<0||n>max)throw new IOException("Invalid snapshot");return n;}
    private static byte[] bytes(ByteBuffer b,int max)throws IOException{int n=count(b,max);if(n>b.remaining())throw new IOException("Invalid snapshot");byte[] v=new byte[n];b.get(v);return v;}
    public static long validate(byte[] data)throws IOException{
        if(data.length<32||data.length>65536)throw new IOException("Invalid snapshot");
        try{
            ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x47534131||b.getInt()!=1)throw new IOException("Unsupported snapshot");
            long user=b.getLong();int main=b.getInt();b.getInt();int test=b.getInt(),apiId=b.getInt(),n=count(b,16);boolean found=false;HashSet<Integer> ids=new HashSet<>();
            if(user<=0||apiId<=0||test<0||test>1||n==0)throw new IOException("Invalid snapshot");
            for(int i=0;i<n;i++){
                int id=b.getInt(),authorized=b.getInt(),addresses=count(b,128);
                if(id<=0||id>1000||!ids.add(id)||authorized<0||authorized>1)throw new IOException("Invalid DC");
                for(int a=0;a<addresses;a++){
                    int group=b.getInt();String ip=new String(bytes(b,255),StandardCharsets.US_ASCII);int port=b.getInt();b.getInt();bytes(b,512);
                    if(group<0||group>3||port<=0||port>65535||!ip.matches("[0-9a-fA-F:.]+"))throw new IOException("Invalid address");
                }
                int keyLength=count(b,256);if(keyLength!=0&&keyLength!=256||keyLength>b.remaining())throw new IOException("Invalid key");b.position(b.position()+keyLength);
                int salts=count(b,64);for(int s=0;s<salts;s++){int since=b.getInt(),until=b.getInt();b.getLong();if(until<=since)throw new IOException("Invalid salt");}
                if(id==main)found=keyLength==256&&authorized==1&&addresses>0;
            }
            if(!found||b.hasRemaining())throw new IOException("Invalid snapshot");return user;
        }catch(BufferUnderflowException|IllegalArgumentException e){throw new IOException("Invalid snapshot");}
    }
    public static int apiId(byte[] data)throws IOException{validate(data);return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(28);}
    private GhostAuthSnapshot(){}
}
