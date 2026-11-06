package org.telegram.tgnet;

/** Official MTProto auth.importBotAuthorization, not the HTTP Bot API. */
public final class GhostBotAuthorization extends TLObject {
    public String token;
    @Override public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
        return TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception);
    }
    @Override public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(0x67a3ff2c); stream.writeInt32(0);
        stream.writeInt32(org.telegram.messenger.BuildVars.APP_ID);
        stream.writeString(org.telegram.messenger.BuildVars.APP_HASH);
        stream.writeString(token);
    }
}
