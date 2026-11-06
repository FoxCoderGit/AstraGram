import org.telegram.messenger.GhostExteraSupporterRegistry;
import org.json.JSONException;
import java.nio.file.*;

public final class GhostExteraSupportersSmoke {
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError("Extera case "+(checks+1));checks++;}
    static void reject(String input)throws Exception{
        try{GhostExteraSupporterRegistry.parse(input);throw new AssertionError("Invalid response accepted");}catch(JSONException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        String supporter="{\"id\":5330087923,\"type\":\"USER\",\"status\":\"SUPPORTER\",\"badge\":{\"documentId\":5391059537102927631}}";
        String developer="{\"id\":22,\"type\":\"USER\",\"status\":\"DEVELOPER\",\"badge\":{\"documentId\":5391059537102927631}}";
        String chat="{\"id\":33,\"type\":\"CHAT\",\"status\":\"SUPPORTER\",\"badge\":{\"documentId\":5391059537102927631}}";
        GhostExteraSupporterRegistry result=GhostExteraSupporterRegistry.parse("["+supporter+","+developer+","+chat+"]");
        check(result.documents.size()==1);check(result.documents.get(5330087923L)==5391059537102927631L);
        check(!result.documents.containsKey(22L));check(!result.documents.containsKey(33L));
        check(GhostExteraSupporterRegistry.parse("[]").documents.isEmpty());
        check(GhostExteraSupporterRegistry.parse("["+supporter.replace("SUPPORTER","UNKNOWN")+"]").documents.isEmpty());
        check(GhostExteraSupporterRegistry.parse("[{\"id\":11,\"type\":\"USER\",\"status\":\"SUPPORTER\"}]").documents.isEmpty());
        reject("{}");reject(null);reject("["+supporter+","+supporter+"]");
        reject("["+supporter.replace("5330087923","-11")+"]");
        reject("["+supporter.replace("5330087923","9223372036854775808")+"]");
        reject("["+supporter.replace("5330087923","11.5")+"]");
        reject("["+supporter.replace("5391059537102927631","0")+"]");
        reject("["+supporter+","+developer.replace("22","5330087923")+"]");
        try{result.documents.clear();throw new AssertionError();}catch(UnsupportedOperationException expected){checks++;}
        if(args.length>0){
            String live=new String(Files.readAllBytes(Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8);
            GhostExteraSupporterRegistry actual=GhostExteraSupporterRegistry.parse(live);
            check(!actual.documents.isEmpty());System.out.println("LIVE profiles accepted; eligible USER/SUPPORTER count="+actual.documents.size());
        }
        System.out.println("PASS: "+checks+" extera supporter checks");
    }
}
