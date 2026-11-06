"""Device-only native probes; never invokes a Telegram send/delete API."""
def run(context):
    from org.telegram.messenger import MessageObject, GhostMessageActions, GhostForwardTools, UserConfig
    from org.telegram.tgnet import TLRPC
    from org.telegram.ui.Cells import ChatMessageCell
    from java import jclass
    from hook_utils import set_private_field
    import plugin_runtime as runtime
    account = int(UserConfig.selectedAccount)
    owner = TLRPC.TL_message()
    owner.id = -2146000501  # Excluded from the persistent reference index.
    owner.date = 1700000000
    owner.message = ''
    owner.peer_id = TLRPC.TL_peerUser()
    owner.peer_id.user_id = 2146000501
    owner.out = True
    owner.flags = 512
    owner.media = TLRPC.TL_messageMediaDocument()
    owner.media.flags = 1
    owner.media.document = TLRPC.TL_document()
    document = owner.media.document
    document.id = 2146000501001
    document.access_hash = 1
    document.file_reference = b''
    document.mime_type = 'video/mp4'
    document.dc_id = 2
    document.size = 1000
    attribute = TLRPC.TL_documentAttributeVideo()
    attribute.round_message = True
    attribute.flags = 1
    attribute.w = 240
    attribute.h = 240
    attribute.duration = 7.0
    document.attributes.add(attribute)
    message = MessageObject(account, owner, False, False)
    assert message.isRoundVideo()
    frame = GhostMessageActions.fullFrame(message)
    assert not frame.isRoundVideo() and frame.isVideo()
    assert message.isRoundVideo() and attribute.round_message
    assert frame.getDocument().id == document.id

    cell = ChatMessageCell(context, account)
    set_private_field(cell, 'currentMessageObject', message)
    method = cell.getClass().getDeclaredMethod('measureTime', MessageObject.getClass())
    method.setAccessible(True)
    from hook_utils import get_private_field
    author = owner.post_author
    method.invoke(cell, message)
    text = str(get_private_field(cell, 'currentTimeString'))
    assert '\u2248Android' in text, 'Built-in round label did not appear: ' + text
    assert owner.post_author == author
    method.invoke(cell, message)
    assert str(get_private_field(cell, 'currentTimeString')).count('\u2248Android') == 1
    assert GhostForwardTools.selectionLimit() > 100
    assert not any(key in runtime._plugins for key in ('zwyNoForwardLimit', 'round_client_detector'))
    return 'PASS: detached full-frame document; built-in native round label; repeated layout; original unchanged; unlimited native selection without plugins'
