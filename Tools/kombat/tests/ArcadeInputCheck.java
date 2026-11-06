import org.telegram.messenger.GhostKombatInputBuffer;
import org.telegram.messenger.GhostKombatTiming;

public final class ArcadeInputCheck {
    public static void main(String[] args){
        GhostKombatInputBuffer input=new GhostKombatInputBuffer();
        int[] combo={64,0,64,0,32,160,128,130};
        for(int edge:combo){input.offer(edge);input.offer(edge);}
        for(int edge:combo)if(input.poll(0)!=edge)throw new AssertionError("Lost directional/button edge");
        if(input.poll(1024)!=1024)throw new AssertionError("Held block lost");
        for(int i=0;i<100;i++)input.offer(i%2==0?64:128);
        int count=0;while(input.poll(-1)!=-1)if(++count>8)throw new AssertionError("Unbounded stale input delay");
        input.clear();if(input.poll(0)!=0)throw new AssertionError("Cancellation left a held direction");
        for(long rtt:new long[]{0,20,80,150,250}){
            int lead=GhostKombatTiming.inputLead(rtt);
            double oneWayFrames=rtt*53.20494842529297/2000.0;
            if(lead<2*oneWayFrames+3||lead>22)throw new AssertionError("Input reaches authoritative server late");
        }
        System.out.println("PASS: double taps, neutral edges, quarter circle + punch, deduplication, held block, bounded queue, cancellation and 0–250 ms RTT scheduling.");
    }
}
