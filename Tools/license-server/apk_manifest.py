"""Read package identity from Android binary XML without executing APK code."""
import struct
import zipfile


def identity(path):
    with zipfile.ZipFile(path) as apk:
        entry = apk.getinfo('AndroidManifest.xml')
        if entry.file_size > 4 * 1024 * 1024:
            raise ValueError('manifest too large')
        data = apk.read(entry)
    def u16(pos): return struct.unpack_from('<H', data, pos)[0]
    def u32(pos): return struct.unpack_from('<I', data, pos)[0]
    if len(data) < 8 or u16(0) != 3 or u32(4) != len(data):
        raise ValueError('invalid binary manifest')
    strings = []
    def string(index):
        if index == 0xffffffff: return None
        if index >= len(strings): raise ValueError('invalid string index')
        return strings[index]
    pos = u16(2)
    while pos + 8 <= len(data):
        kind, header, size = u16(pos), u16(pos + 2), u32(pos + 4)
        if header < 8 or size < header or pos + size > len(data): raise ValueError('invalid XML chunk')
        if kind == 1:
            if header < 28: raise ValueError('invalid string pool')
            count, flags, start = u32(pos + 8), u32(pos + 16), u32(pos + 20)
            if count > 100000 or header + 4 * count > size: raise ValueError('invalid string offsets')
            def length8(at):
                value = data[at]
                return (((value & 127) << 8) | data[at + 1], at + 2) if value & 128 else (value, at + 1)
            def length16(at):
                value = u16(at)
                return (((value & 32767) << 16) | u16(at + 2), at + 4) if value & 32768 else (value, at + 2)
            for i in range(count):
                at = pos + start + u32(pos + header + i * 4)
                if not pos <= at < pos + size: raise ValueError('invalid string location')
                if flags & 0x100:
                    _, at = length8(at); length, at = length8(at); end = at + length
                    if end >= pos + size or data[end] != 0: raise ValueError('invalid UTF-8 string')
                    strings.append(data[at:end].decode('utf-8'))
                else:
                    length, at = length16(at); end = at + length * 2
                    if end + 2 > pos + size or u16(end) != 0: raise ValueError('invalid UTF-16 string')
                    strings.append(data[at:end].decode('utf-16-le'))
        elif kind == 0x102:
            if header < 16 or size < header + 20: raise ValueError('invalid element')
            extension = pos + header
            if string(u32(extension + 4)) == 'manifest':
                attr_start, attr_size, count = u16(extension + 8), u16(extension + 10), u16(extension + 12)
                if attr_size < 20 or extension + attr_start + count * attr_size > pos + size:
                    raise ValueError('invalid attributes')
                values = {}
                for i in range(count):
                    at = extension + attr_start + attr_size * i
                    namespace, name = string(u32(at)), string(u32(at + 4))
                    raw, value_type, value = u32(at + 8), data[at + 15], u32(at + 16)
                    if namespace is None and name == 'package' or namespace == 'http://schemas.android.com/apk/res/android' and name in ('versionCode', 'versionCodeMajor', 'versionName'):
                        values[name] = string(raw) if raw != 0xffffffff else string(value) if value_type == 3 else value
                package = values['package']; minor = int(values['versionCode']); major = int(values.get('versionCodeMajor', 0))
                code = (major << 32) | minor
                if not isinstance(package, str) or not 0 < code < 2**63: raise ValueError('invalid package version')
                return {'package': package, 'version_code': code, 'version': str(values.get('versionName', ''))}
        pos += size
    raise ValueError('manifest element not found')
