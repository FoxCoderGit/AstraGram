package org.telegram.messenger;

import java.util.HashMap;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public final class GhostSchedulingTest {
    private static int checks;
    private static void check(boolean b, String message) { checks++; if (!b) throw new AssertionError(message); }
    public static void main(String[] args) {
        int now = 1793000000;
        check(GhostSchedulingPolicy.deadline(now, 0) == now + 12, "text minimum");
        check(GhostSchedulingPolicy.delay(-1) == 12, "unknown size");
        check(GhostSchedulingPolicy.delay(10 * 1048576L) == 45, "media estimate");
        check(GhostSchedulingPolicy.delay(Long.MAX_VALUE) == 86400, "bounded estimate");
        check(GhostSchedulingPolicy.refresh(now - 500, now) == now + 12, "expired upload");
        check(GhostSchedulingPolicy.refresh(now + 300, now) == now + 300, "future date retained");
        check(GhostSchedulingPolicy.tooSoon(0, now), "immediate bypass");
        check(GhostSchedulingPolicy.tooSoon(now - 30, now), "past bypass");
        check(GhostSchedulingPolicy.tooSoon(now + 10, now), "server immediate window");
        check(!GhostSchedulingPolicy.tooSoon(now + 12, now), "safe deadline blocked");
        check(!GhostSchedulingPolicy.tooSoon(0x7ffffffe, now), "when-online schedule changed");
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.params = new HashMap<>(); message.params.put(GhostScheduledMessages.MARKER, "1");
        TLObject[] requests = { new TLRPC.TL_messages_sendMessage(), new TLRPC.TL_messages_sendMedia(),
            new TLRPC.TL_messages_sendMultiMedia(), new TLRPC.TL_messages_forwardMessages(), new TLRPC.TL_messages_sendInlineBotResult() };
        for (TLObject request : requests) {
            GhostScheduledMessages.refreshAt(request, message, now);
            try {
                int date = request.getClass().getField("schedule_date").getInt(request);
                int flags = request.getClass().getField("flags").getInt(request);
                check(date == now + 12 && (flags & 1024) != 0, "request not scheduled: " + request.getClass());
                check(message.date == date, "local and server dates diverged");
                GhostScheduledMessages.refreshAt(request, message, now + 120);
                check(request.getClass().getField("schedule_date").getInt(request) == now + 132, "retry expired");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        TLRPC.TL_messages_sendMessage manual = new TLRPC.TL_messages_sendMessage();
        manual.schedule_date = now + 500; manual.message = "test"; manual.silent = true;
        manual.reply_to = new TLRPC.TL_inputReplyToMessage(); manual.reply_to.reply_to_msg_id = 42;
        TLRPC.TL_message unmarked = new TLRPC.TL_message();
        GhostScheduledMessages.refreshAt(manual, unmarked, now);
        check(manual.schedule_date == now + 500 && unmarked.date == 0, "explicit schedule changed");
        GhostScheduledMessages.refreshAt(manual, message, now);
        check(manual.silent && manual.message.equals("test") && manual.reply_to.reply_to_msg_id == 42, "content/reply changed");
        int oldDate = message.date;
        GhostScheduledMessages.refreshAt(new TLRPC.TL_messages_editMessage(), message, now + 1000);
        check(message.date == oldDate, "edit treated as send");
        System.out.println("Ghost scheduling: " + checks + " checks passed (real request classes; no RPC).");
    }
}
