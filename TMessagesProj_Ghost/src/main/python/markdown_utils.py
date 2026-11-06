"""The reference SDK's stack-based Markdown dialect (UTF-16 entity offsets)."""
import re
from dataclasses import dataclass
from enum import Enum

__all__ = ['parse_markdown']


class TLEntityType(Enum):
    CODE = 'code'
    PRE = 'pre'
    STRIKETHROUGH = 'strikethrough'
    TEXT_LINK = 'text_link'
    BOLD = 'bold'
    ITALIC = 'italic'
    UNDERLINE = 'underline'
    SPOILER = 'spoiler'
    CUSTOM_EMOJI = 'custom_emoji'


_ENTITY_CLASSES = {TLEntityType.CODE: 'Code', TLEntityType.PRE: 'Pre',
                   TLEntityType.STRIKETHROUGH: 'Strike', TLEntityType.TEXT_LINK: 'TextUrl',
                   TLEntityType.BOLD: 'Bold', TLEntityType.ITALIC: 'Italic',
                   TLEntityType.UNDERLINE: 'Underline', TLEntityType.SPOILER: 'Spoiler',
                   TLEntityType.CUSTOM_EMOJI: 'CustomEmoji'}
try:
    from org.telegram.tgnet import TLRPC
    TLRPC_ENTITIES_MAP = {kind: getattr(TLRPC, 'TL_messageEntity' + name) for kind, name in _ENTITY_CLASSES.items()}
except ImportError:
    TLRPC_ENTITIES_MAP = {}


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    language: str = None
    url: str = None
    document_id: int = None

    def to_tlrpc_object(self):
        from org.telegram.tgnet import TLRPC
        result = getattr(TLRPC, 'TL_messageEntity' + _ENTITY_CLASSES[self.type])()
        result.offset, result.length = self.offset, self.length
        for name in ('language', 'url', 'document_id'):
            value = getattr(self, name)
            if value is not None: setattr(result, name, value)
        return result


@dataclass
class ParsedMessage:
    text: str
    entities: tuple


def count_chars_until(string, stop_chars, start_index=0):
    position = start_index
    while position < len(string) and string[position] not in stop_chars: position += 1
    return position - start_index


def get_utf16_code_unit_offset(text, python_char_index):
    return len(text[:python_char_index].encode('utf-16-le', 'surrogatepass')) // 2


def parse_markdown(markdown):
    markdown = markdown.replace('\r\n', '\n').strip()
    message, entities, stack, offset = '', [], [], 0
    markers = {'*': TLEntityType.BOLD, '_': TLEntityType.ITALIC, '__': TLEntityType.UNDERLINE,
               '~': TLEntityType.STRIKETHROUGH, '||': TLEntityType.SPOILER}
    while offset < len(markdown):
        amount = count_chars_until(markdown, '*_~`[]|!\\', offset)
        message += markdown[offset:offset + amount]
        offset += amount
        if offset == len(markdown): break
        marker = markdown[offset]
        start = offset
        offset += 1
        following = markdown[offset:offset + 1]
        if marker == '\\':
            message += following or marker
            if following: offset += 1
            continue
        if marker == '[':
            cursor, label = offset, ''
            while cursor < len(markdown) and markdown[cursor] != ']':
                if markdown[cursor] == '\\' and cursor + 1 < len(markdown): cursor += 1
                label += markdown[cursor]
                cursor += 1
            if cursor >= len(markdown) or markdown[cursor + 1:cursor + 2] != '(':
                message += '['
                continue
            url_start = cursor + 2
            cursor, url = url_start, ''
            while cursor < len(markdown) and markdown[cursor] != ')':
                if markdown[cursor] == '\\' and cursor + 1 < len(markdown): cursor += 1
                url += markdown[cursor]
                cursor += 1
            if cursor == len(markdown):
                message += '[' + label + ']('
                offset = url_start
                continue
            at = len(message)
            message += label
            offset = cursor + 1
            if label:
                entities.append(RawEntity(TLEntityType.CUSTOM_EMOJI if url.isdigit() else TLEntityType.TEXT_LINK,
                                          at, len(label), document_id=int(url) if url.isdigit() else None,
                                          url=None if url.isdigit() else url))
            continue
        if marker == '`':
            token, language = '`', None
            if markdown[start:start + 3] == '```':
                token, offset = '```', start + 3
                match = re.match(r'([^\s\n]+)', markdown[offset:])
                if match:
                    language = match.group(1)
                    offset += len(language)
                if markdown[offset:offset + 1] == '\n': offset += 1
            close = markdown.find(token, offset)
            if close < 0: raise SyntaxError('Unclosed ' + token + ' opened near markdown offset ' + str(start) + '!')
            piece = markdown[offset:close]
            at = len(message)
            message += piece
            length = len(piece.rstrip(' \r\n'))
            if length: entities.append(RawEntity(TLEntityType.PRE if token == '```' else TLEntityType.CODE, at, length, language=language))
            offset = close + len(token)
            continue
        if marker in ('_', '|') and following == marker:
            marker += marker
            offset += 1
        if marker not in markers:
            message += marker
            continue
        if marker == '_' and re.search(r'@\w+$', message):
            message += marker
            continue
        if stack and stack[-1][0] == marker:
            _, at = stack.pop()
            length = len(message[at:].rstrip(' \r\n'))
            if length: entities.append(RawEntity(markers[marker], at, length))
        else: stack.append((marker, len(message)))
    if stack:
        raise SyntaxError('Found unclosed markdown elements: ' + ', '.join(repr(marker) + ' at message offset ' + str(at) for marker, at in stack))
    result = []
    for entity in entities:
        at = get_utf16_code_unit_offset(message, entity.offset)
        length = get_utf16_code_unit_offset(message, entity.offset + entity.length) - at
        result.append(RawEntity(entity.type, at, length, entity.language, entity.url, entity.document_id))
    return ParsedMessage(message, tuple(result))
