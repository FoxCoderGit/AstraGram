import org.ghostgram.messenger.license.UpdateSpec;
import org.json.JSONObject;

public final class UpdateSpecSmoke {
    private static int checks;
    static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    static JSONObject release() throws Exception {
        String id="0123456789abcdef0123456789abcdef";
        return new JSONObject().put("id",id).put("path","/downloads/"+id+".apk").put("sha256",new String(new char[64]).replace('\0','a'))
            .put("version_code",7131L).put("size",151000000L).put("version","name-without-ghost-number").put("build",99999);
    }
    static void reject(String field,Object value)throws Exception{
        JSONObject input=release().put(field,value);
        try{new UpdateSpec(input);throw new AssertionError("accepted "+field);}catch(IllegalArgumentException|org.json.JSONException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        UpdateSpec spec=new UpdateSpec(release());
        check(spec.newerThan(7130));check(!spec.newerThan(7131));check(!spec.newerThan(7132));check(spec.canSkip());
        check(!new UpdateSpec(release().put("mandatory",true)).canSkip());
        check(new UpdateSpec(release().put("version_code",(1L<<32)+1)).newerThan(Integer.MAX_VALUE));
        reject("version_code",0);reject("version_code",-1);reject("version_code",JSONObject.NULL);
        reject("id","../other");reject("path","https://other.example/app.apk");reject("sha256","wrong");
        reject("size",0);reject("size",200000001L);reject("version","");
        check(UpdateSpec.validRange("bytes 5-9/10",5,10));check(!UpdateSpec.validRange("bytes 0-9/10",5,10));
        check(!UpdateSpec.validRange("bytes 5-8/10",5,10));check(!UpdateSpec.validRange(null,5,10));
        System.out.println("PASS: "+checks+" update metadata/range checks");
    }
}
