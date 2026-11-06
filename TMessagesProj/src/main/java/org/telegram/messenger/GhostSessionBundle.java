package org.telegram.messenger;

import java.io.*;
import java.util.*;

/** Plaintext exists only in memory and in the client's normal private authorization storage. */
public final class GhostSessionBundle {
    private static final class Memory extends ByteArrayOutputStream {void wipe(){Arrays.fill(buf,(byte)0);}}
    public static final class Entry implements AutoCloseable {
        public final long id;public final byte[] user,auth;
        public Entry(long id,byte[] user,byte[] auth){this.id=id;this.user=user;this.auth=auth;}
        @Override public void close(){Arrays.fill(user,(byte)0);Arrays.fill(auth,(byte)0);}
    }
    private static void blob(DataOutputStream out,byte[] v)throws IOException{out.writeInt(v.length);out.write(v);}
    private static byte[] blob(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<=0||n>max||n>in.available())throw new IOException("Invalid session bundle");byte[] v=new byte[n];in.readFully(v);return v;}
    public static byte[] encode(List<Entry> entries)throws IOException{
        if(entries.isEmpty()||entries.size()>64)throw new IOException("Account count");
        Memory bytes=new Memory();try{DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x47534231);out.writeInt(1);out.writeInt(entries.size());HashSet<Long> ids=new HashSet<>();
            for(Entry e:entries){if(!ids.add(e.id)||e.id!=GhostAuthSnapshot.validate(e.auth)||e.user.length==0||e.user.length>65536)throw new IOException("Invalid account");out.writeLong(e.id);blob(out,e.user);blob(out,e.auth);}
            return bytes.toByteArray();
        }finally{bytes.wipe();}
    }
    public static List<Entry> decode(byte[] data)throws IOException{
        ArrayList<Entry> result=new ArrayList<>();
        try{
            if(data.length>GhostSessionCrypto.MAX_FILE)throw new IOException("Size");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));if(in.readInt()!=0x47534231||in.readInt()!=1)throw new IOException("Version");int n=in.readInt();if(n<=0||n>64)throw new IOException("Account count");HashSet<Long> ids=new HashSet<>();
            for(int i=0;i<n;i++){
                long id=in.readLong();byte[] user=blob(in,65536),auth=null;
                try{auth=blob(in,65536);if(id<=0||!ids.add(id)||id!=GhostAuthSnapshot.validate(auth))throw new IOException("Account mismatch");result.add(new Entry(id,user,auth));}
                catch(Exception e){Arrays.fill(user,(byte)0);if(auth!=null)Arrays.fill(auth,(byte)0);throw e;}
            }
            if(in.available()!=0)throw new IOException("Trailing data");return result;
        }catch(IOException|RuntimeException e){for(Entry entry:result)entry.close();throw new IOException("Invalid session bundle");}
    }
    private GhostSessionBundle(){}
}
