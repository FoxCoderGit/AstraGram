package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** Current participant records carry a Peer; some self/older records carry user_id. */
public final class GhostCloudParticipants {
    private GhostCloudParticipants() { }
    public static boolean onlyOwner(TLRPC.channels_ChannelParticipants response,long owner) {
        if(!(response instanceof TLRPC.TL_channels_channelParticipants)||owner<=0
                ||response.count!=1||response.participants.size()!=1)return false;
        TLRPC.ChannelParticipant participant=response.participants.get(0);
        if(participant==null)return false;
        if(participant.peer!=null)return participant.peer instanceof TLRPC.TL_peerUser && participant.peer.user_id==owner;
        return participant.user_id==owner;
    }
}
