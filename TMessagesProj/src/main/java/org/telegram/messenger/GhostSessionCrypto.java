package org.telegram.messenger;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Portable encrypted envelope. The supplied KDF is OpenSSL PBKDF2-HMAC-SHA512. */
public final class GhostSessionCrypto {
    public static final int ITERATIONS=240000,MAX_FILE=8*1024*1024;
    private static final byte[] MAGIC={'G','H','O','S','T','S','E','S'};
    private static final int HEADER=8+4+4+4+32+12+4;
    public interface Deriver {byte[] derive(char[] password,byte[] salt,int iterations)throws GeneralSecurityException;}
    public static final class FormatException extends IOException {public final String kind;FormatException(String k){super(k);kind=k;}}
    private GhostSessionCrypto(){}
    public static byte[] encrypt(byte[] plain,char[] password,Deriver deriver)throws IOException,GeneralSecurityException{
        if(plain.length==0||plain.length>MAX_FILE-HEADER-16)throw new FormatException("size");
        byte[] salt=new byte[32],nonce=new byte[12];SecureRandom random=new SecureRandom();random.nextBytes(salt);random.nextBytes(nonce);
        ByteBuffer header=ByteBuffer.allocate(HEADER);header.put(MAGIC).putInt(1).putInt(1).putInt(ITERATIONS).put(salt).put(nonce).putInt(plain.length+16);
        byte[] key=deriver.derive(password,salt,ITERATIONS);
        try{
            if(key.length!=32)throw new GeneralSecurityException("KDF");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(header.array());
            byte[] encrypted=cipher.doFinal(plain);byte[] result=new byte[HEADER+encrypted.length];System.arraycopy(header.array(),0,result,0,HEADER);System.arraycopy(encrypted,0,result,HEADER,encrypted.length);Arrays.fill(encrypted,(byte)0);return result;
        }finally{Arrays.fill(key,(byte)0);}
    }
    public static byte[] decrypt(byte[] file,char[] password,Deriver deriver)throws IOException,GeneralSecurityException{
        if(file.length<HEADER+16||file.length>MAX_FILE)throw new FormatException("size");
        ByteBuffer h=ByteBuffer.wrap(file);byte[] magic=new byte[8];h.get(magic);if(!Arrays.equals(magic,MAGIC))throw new FormatException("format");
        if(h.getInt()!=1||h.getInt()!=1)throw new FormatException("version");
        int rounds=h.getInt();if(rounds!=ITERATIONS)throw new FormatException("kdf");
        byte[] salt=new byte[32],nonce=new byte[12];h.get(salt);h.get(nonce);if(h.getInt()!=file.length-HEADER)throw new FormatException("size");
        byte[] key=deriver.derive(password,salt,rounds);
        try{
            if(key.length!=32)throw new GeneralSecurityException("KDF");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(file,0,HEADER);
            return cipher.doFinal(file,HEADER,file.length-HEADER);
        }finally{Arrays.fill(key,(byte)0);}
    }
    public static byte[] readBounded(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[16384];int n;
        while((n=input.read(block))!=-1){if(out.size()+n>MAX_FILE)throw new FormatException("size");out.write(block,0,n);}return out.toByteArray();
    }
}
