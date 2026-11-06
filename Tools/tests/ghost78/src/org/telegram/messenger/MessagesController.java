package org.telegram.messenger;
import org.telegram.tgnet.*;
public class MessagesController {
 public static MessagesController getInstance(int account){return new MessagesController();}
 public void onGhostModeChanged(boolean b){}
 public TLRPC.Chat getChat(long id){return null;}
 public TLRPC.InputChannel getInputChannel(long id){return null;}
 public TLRPC.InputPeer getInputPeer(long id){return null;}
 public ConnectionsManager getConnectionsManager(){throw new AssertionError("Unexpected RPC");}
}
