import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import org.telegram.messenger.*;

public final class SessionCryptoTest {
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    interface Task{void run()throws Exception;}
    static void reject(Task action)throws Exception{try{action.run();throw new AssertionError("Accepted invalid data");}catch(java.io.IOException|GeneralSecurityException expected){checks++;}}
    static byte[] derive(char[] password,byte[] salt,int n)throws GeneralSecurityException{PBEKeySpec spec=new PBEKeySpec(password,salt,n,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}}
    public static void main(String[] args)throws Exception{
        byte[] auth=Files.readAllBytes(Paths.get(args[0]));check(GhostAuthSnapshot.validate(auth)==1234567);check(GhostAuthSnapshot.apiId(auth)==28804573);
        byte[] other=auth.clone();ByteBuffer.wrap(other).order(ByteOrder.LITTLE_ENDIAN).putLong(8,7654321);
        List<GhostSessionBundle.Entry> entries=Arrays.asList(new GhostSessionBundle.Entry(1234567,new byte[]{1,2,3},auth),new GhostSessionBundle.Entry(7654321,new byte[]{4,5,6},other));
        byte[] plain=GhostSessionBundle.encode(entries);char[] pass="Сложный пароль 🔐 123".toCharArray();
        byte[] encrypted=GhostSessionCrypto.encrypt(plain,pass,SessionCryptoTest::derive);
        byte[] repeated=GhostSessionCrypto.encrypt(plain,pass,SessionCryptoTest::derive);check(!Arrays.equals(encrypted,repeated));
        byte[] decrypted=GhostSessionCrypto.decrypt(encrypted,pass,SessionCryptoTest::derive);check(Arrays.equals(plain,decrypted));
        List<GhostSessionBundle.Entry> restored=GhostSessionBundle.decode(decrypted);check(restored.size()==2&&restored.get(1).id==7654321);for(GhostSessionBundle.Entry e:restored)e.close();
        reject(()->GhostSessionCrypto.decrypt(encrypted,"wrong password".toCharArray(),SessionCryptoTest::derive));
        byte[] corrupt=encrypted.clone();corrupt[corrupt.length-1]^=1;reject(()->GhostSessionCrypto.decrypt(corrupt,pass,SessionCryptoTest::derive));
        byte[] header=encrypted.clone();header[22]^=1;reject(()->GhostSessionCrypto.decrypt(header,pass,SessionCryptoTest::derive));
        byte[] version=encrypted.clone();version[11]=2;reject(()->GhostSessionCrypto.decrypt(version,pass,SessionCryptoTest::derive));
        byte[] work=encrypted.clone();work[19]=0;reject(()->GhostSessionCrypto.decrypt(work,pass,SessionCryptoTest::derive));
        reject(()->GhostSessionCrypto.decrypt(Arrays.copyOf(encrypted,encrypted.length-1),pass,SessionCryptoTest::derive));
        reject(()->GhostSessionCrypto.readBounded(new java.io.ByteArrayInputStream(new byte[GhostSessionCrypto.MAX_FILE+1])));
        reject(()->GhostSessionBundle.encode(Arrays.asList(entries.get(0),entries.get(0))));
        reject(()->GhostSessionBundle.encode(Arrays.asList(new GhostSessionBundle.Entry(9,new byte[]{1},auth))));
        for(int n=0;n<auth.length;n++){final int length=n;reject(()->GhostAuthSnapshot.validate(Arrays.copyOf(auth,length)));}
        byte[] tail=Arrays.copyOf(plain,plain.length+1);reject(()->GhostSessionBundle.decode(tail));
        for(GhostSessionBundle.Entry e:entries)e.close();check(Arrays.equals(auth,new byte[auth.length]));Arrays.fill(pass,'\0');
        System.out.println("PASS: "+checks+" portable format/crypto checks; native C++ fixture matches Java; no real accounts used");
    }
}
