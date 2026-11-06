package org.telegram.tgnet;

import java.util.ArrayList;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.tgnet.tl.TL_account;

/** Bot-only constructors omitted by the upstream user client. Schema: core.telegram.org/type/Update. */
public final class GhostBotUpdates {
    private GhostBotUpdates() {}
    public static abstract class BotUpdate extends TLRPC.Update {
        public int flags, qts, date, msg_id;
        public long query_id, user_id, chat_instance;
        public TLRPC.Peer peer;
        public byte[] data, payload;
        public String game_short_name;
        public boolean stopped;
    }
    public static class UpdateBotInlineQuery extends BotUpdate {
        public static final int constructor = 0x496f379c;
        public String query;
        public TLRPC.GeoPoint geo;
        public TLRPC.InlineQueryPeerType peer_type;
        public String offset;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            query = stream.readString(exception);
            if ((flags & 1) != 0) geo = TLRPC.GeoPoint.TLdeserialize(stream, stream.readInt32(exception), exception);
            if ((flags & 2) != 0) peer_type = TLRPC.InlineQueryPeerType.TLdeserialize(stream, stream.readInt32(exception), exception);
            offset = stream.readString(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeString(query);
            if ((flags & 1) != 0) geo.serializeToStream(stream);
            if ((flags & 2) != 0) peer_type.serializeToStream(stream);
            stream.writeString(offset);
        }
    }
    public static class UpdateBotInlineSend extends BotUpdate {
        public TLRPC.TL_inputBotInlineMessageID msg_id;
        public static final int constructor = 0x12f12a07;
        public String query;
        public TLRPC.GeoPoint geo;
        public String id;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            user_id = stream.readInt64(exception);
            query = stream.readString(exception);
            if ((flags & 1) != 0) geo = TLRPC.GeoPoint.TLdeserialize(stream, stream.readInt32(exception), exception);
            id = stream.readString(exception);
            if ((flags & 2) != 0) msg_id = TLRPC.TL_inputBotInlineMessageID.TLdeserialize(stream, stream.readInt32(exception), exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(user_id);
            stream.writeString(query);
            if ((flags & 1) != 0) geo.serializeToStream(stream);
            stream.writeString(id);
            if ((flags & 2) != 0) msg_id.serializeToStream(stream);
        }
    }
    public static class UpdateBotCallbackQuery extends BotUpdate {
        public static final int constructor = 0xb9cfc48d;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            msg_id = stream.readInt32(exception);
            chat_instance = stream.readInt64(exception);
            if ((flags & 1) != 0) data = stream.readByteArray(exception);
            if ((flags & 2) != 0) game_short_name = stream.readString(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            peer.serializeToStream(stream);
            stream.writeInt32(msg_id);
            stream.writeInt64(chat_instance);
            if ((flags & 1) != 0) stream.writeByteArray(data);
            if ((flags & 2) != 0) stream.writeString(game_short_name);
        }
    }
    public static class UpdateInlineBotCallbackQuery extends BotUpdate {
        public TLRPC.TL_inputBotInlineMessageID msg_id;
        public static final int constructor = 0x691e9052;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            msg_id = TLRPC.TL_inputBotInlineMessageID.TLdeserialize(stream, stream.readInt32(exception), exception);
            chat_instance = stream.readInt64(exception);
            if ((flags & 1) != 0) data = stream.readByteArray(exception);
            if ((flags & 2) != 0) game_short_name = stream.readString(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            msg_id.serializeToStream(stream);
            stream.writeInt64(chat_instance);
            if ((flags & 1) != 0) stream.writeByteArray(data);
            if ((flags & 2) != 0) stream.writeString(game_short_name);
        }
    }
    public static class UpdateBotWebhookJSON extends BotUpdate {
        public TLRPC.TL_dataJSON data;
        public static final int constructor = 0x8317c0c3;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            data = TLRPC.TL_dataJSON.TLdeserialize(stream, stream.readInt32(exception), exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            data.serializeToStream(stream);
        }
    }
    public static class UpdateBotWebhookJSONQuery extends BotUpdate {
        public TLRPC.TL_dataJSON data;
        public static final int constructor = 0x9b9240a6;
        public int timeout;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            query_id = stream.readInt64(exception);
            data = TLRPC.TL_dataJSON.TLdeserialize(stream, stream.readInt32(exception), exception);
            timeout = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(query_id);
            data.serializeToStream(stream);
            stream.writeInt32(timeout);
        }
    }
    public static class UpdateBotShippingQuery extends BotUpdate {
        public static final int constructor = 0xb5aefd7d;
        public TLRPC.TL_postAddress shipping_address;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            payload = stream.readByteArray(exception);
            shipping_address = TLRPC.TL_postAddress.TLdeserialize(stream, stream.readInt32(exception), exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeByteArray(payload);
            shipping_address.serializeToStream(stream);
        }
    }
    public static class UpdateBotPrecheckoutQuery extends BotUpdate {
        public static final int constructor = 0x8caa9a96;
        public TLRPC.TL_paymentRequestedInfo info;
        public String shipping_option_id;
        public String currency;
        public long total_amount;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            payload = stream.readByteArray(exception);
            if ((flags & 1) != 0) info = TLRPC.TL_paymentRequestedInfo.TLdeserialize(stream, stream.readInt32(exception), exception);
            if ((flags & 2) != 0) shipping_option_id = stream.readString(exception);
            currency = stream.readString(exception);
            total_amount = stream.readInt64(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeByteArray(payload);
            if ((flags & 1) != 0) info.serializeToStream(stream);
            if ((flags & 2) != 0) stream.writeString(shipping_option_id);
            stream.writeString(currency);
            stream.writeInt64(total_amount);
        }
    }
    public static class UpdateBotStopped extends BotUpdate {
        public static final int constructor = 0xc4870a49;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            user_id = stream.readInt64(exception);
            date = stream.readInt32(exception);
            stopped = stream.readBool(exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
            stream.writeInt32(date);
            stream.writeBool(stopped);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotChatInviteRequester extends BotUpdate {
        public static final int constructor = 0x7cb34d79;
        public String about;
        public TLRPC.ExportedChatInvite invite;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            date = stream.readInt32(exception);
            user_id = stream.readInt64(exception);
            about = stream.readString(exception);
            invite = TLRPC.ExportedChatInvite.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
            if ((flags & 1) != 0) query_id = stream.readInt64(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            peer.serializeToStream(stream);
            stream.writeInt32(date);
            stream.writeInt64(user_id);
            stream.writeString(about);
            invite.serializeToStream(stream);
            stream.writeInt32(qts);
            if ((flags & 1) != 0) stream.writeInt64(query_id);
        }
    }
    // Layer 229 and earlier omit flags and the optional query_id.
    public static class UpdateBotChatInviteRequesterLegacy extends UpdateBotChatInviteRequester {
        public static final int constructor = 0x11dfa986;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            date = stream.readInt32(exception);
            user_id = stream.readInt64(exception);
            about = stream.readString(exception);
            invite = TLRPC.ExportedChatInvite.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            stream.writeInt32(date);
            stream.writeInt64(user_id);
            stream.writeString(about);
            invite.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotChatBoost extends BotUpdate {
        public static final int constructor = 0x904dd49c;
        public TL_stories.Boost boost;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            boost = TL_stories.Boost.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            boost.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotMessageReaction extends BotUpdate {
        public static final int constructor = 0xac21d3ce;
        public TLRPC.Peer actor;
        public ArrayList<TLRPC.Reaction> old_reactions = new ArrayList<>();
        public ArrayList<TLRPC.Reaction> new_reactions = new ArrayList<>();
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            msg_id = stream.readInt32(exception);
            date = stream.readInt32(exception);
            actor = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            old_reactions = Vector.deserialize(stream, TLRPC.Reaction::TLdeserialize, exception);
            new_reactions = Vector.deserialize(stream, TLRPC.Reaction::TLdeserialize, exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            stream.writeInt32(msg_id);
            stream.writeInt32(date);
            actor.serializeToStream(stream);
            Vector.serialize(stream, old_reactions);
            Vector.serialize(stream, new_reactions);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotMessageReactions extends BotUpdate {
        public static final int constructor = 0x9cb7759;
        public ArrayList<TLRPC.ReactionCount> reactions = new ArrayList<>();
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            msg_id = stream.readInt32(exception);
            date = stream.readInt32(exception);
            reactions = Vector.deserialize(stream, TLRPC.ReactionCount::TLdeserialize, exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            stream.writeInt32(msg_id);
            stream.writeInt32(date);
            Vector.serialize(stream, reactions);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotBusinessConnect extends BotUpdate {
        public static final int constructor = 0x8ae5c97a;
        public BusinessConnection connection;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            connection = BusinessConnection.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            connection.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotNewBusinessMessage extends BotUpdate {
        public static final int constructor = 0x9ddb347c;
        public String connection_id;
        public TLRPC.Message message;
        public TLRPC.Message reply_to_message;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            connection_id = stream.readString(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if ((flags & 1) != 0) reply_to_message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(connection_id);
            message.serializeToStream(stream);
            if ((flags & 1) != 0) reply_to_message.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotEditBusinessMessage extends BotUpdate {
        public static final int constructor = 0x7df587c;
        public String connection_id;
        public TLRPC.Message message;
        public TLRPC.Message reply_to_message;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            connection_id = stream.readString(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if ((flags & 1) != 0) reply_to_message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(connection_id);
            message.serializeToStream(stream);
            if ((flags & 1) != 0) reply_to_message.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotDeleteBusinessMessage extends BotUpdate {
        public static final int constructor = 0xa02a982e;
        public String connection_id;
        public ArrayList<Integer> messages = new ArrayList<>();
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            connection_id = stream.readString(exception);
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            messages = Vector.deserializeInt(stream, exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(connection_id);
            peer.serializeToStream(stream);
            Vector.serializeInt(stream, messages);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotGuestChatQuery extends BotUpdate {
        public static final int constructor = 0xcdd4093d;
        public TLRPC.Message message;
        public ArrayList<TLRPC.Message> reference_messages = new ArrayList<>();
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if ((flags & 1) != 0) reference_messages = Vector.deserialize(stream, TLRPC.Message::TLdeserialize, exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            message.serializeToStream(stream);
            if ((flags & 1) != 0) Vector.serialize(stream, reference_messages);
            stream.writeInt32(qts);
        }
    }
    public static class UpdateBotStarsSubscription extends BotUpdate {
        public static final int constructor = 0x6c0d8e23;
        public boolean canceled;
        public boolean payment_failed;
        public boolean restored;
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            canceled = ((flags & 1) != 0);
            payment_failed = ((flags & 2) != 0);
            restored = ((flags & 4) != 0);
            user_id = stream.readInt64(exception);
            payload = stream.readByteArray(exception);
            qts = stream.readInt32(exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            flags = canceled ? (flags | 1) : (flags & ~1);
            flags = payment_failed ? (flags | 2) : (flags & ~2);
            flags = restored ? (flags | 4) : (flags & ~4);
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(user_id);
            stream.writeByteArray(payload);
            stream.writeInt32(qts);
        }
    }
    public static class BusinessConnection extends TLObject {
        public static final int constructor = 0x8f34b2f5;
        public int flags;
        public boolean disabled;
        public String connection_id;
        public long user_id;
        public int dc_id;
        public int date;
        public TL_account.TL_businessBotRights rights;
        public static BusinessConnection TLdeserialize(InputSerializedData stream, int magic, boolean exception) {
            return TLObject.TLdeserialize(BusinessConnection.class, magic == constructor || magic == 0x896433b4 ? new BusinessConnection() : null, stream, magic, exception);
        }
        @Override public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            disabled = ((flags & 2) != 0);
            connection_id = stream.readString(exception);
            user_id = stream.readInt64(exception);
            dc_id = stream.readInt32(exception);
            date = stream.readInt32(exception);
            if ((flags & 4) != 0) rights = TL_account.TL_businessBotRights.TLdeserialize(stream, stream.readInt32(exception), exception);
        }
        @Override public void serializeToStream(OutputSerializedData stream) {
            flags = disabled ? (flags | 2) : (flags & ~2);
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(connection_id);
            stream.writeInt64(user_id);
            stream.writeInt32(dc_id);
            stream.writeInt32(date);
            if ((flags & 4) != 0) rights.serializeToStream(stream);
        }
    }
    public static BotUpdate create(int constructor) {
        switch (constructor) {
            case 0x496f379c: return new UpdateBotInlineQuery();
            case 0x12f12a07: return new UpdateBotInlineSend();
            case 0xb9cfc48d: return new UpdateBotCallbackQuery();
            case 0x691e9052: return new UpdateInlineBotCallbackQuery();
            case 0x8317c0c3: return new UpdateBotWebhookJSON();
            case 0x9b9240a6: return new UpdateBotWebhookJSONQuery();
            case 0xb5aefd7d: return new UpdateBotShippingQuery();
            case 0x8caa9a96: return new UpdateBotPrecheckoutQuery();
            case 0xc4870a49: return new UpdateBotStopped();
            case 0x11dfa986: return new UpdateBotChatInviteRequesterLegacy();
            case 0x7cb34d79: return new UpdateBotChatInviteRequester();
            case 0x904dd49c: return new UpdateBotChatBoost();
            case 0xac21d3ce: return new UpdateBotMessageReaction();
            case 0x9cb7759: return new UpdateBotMessageReactions();
            case 0x8ae5c97a: return new UpdateBotBusinessConnect();
            case 0x9ddb347c: return new UpdateBotNewBusinessMessage();
            case 0x7df587c: return new UpdateBotEditBusinessMessage();
            case 0xa02a982e: return new UpdateBotDeleteBusinessMessage();
            case 0xcdd4093d: return new UpdateBotGuestChatQuery();
            case 0x6c0d8e23: return new UpdateBotStarsSubscription();
            default: return null;
        }
    }
}
