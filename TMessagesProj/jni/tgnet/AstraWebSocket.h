#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

namespace astragram {

struct RelayFrame { uint8_t opcode = 0; std::vector<uint8_t> payload; };

class RelayFrameDecoder {
public:
    static constexpr size_t maxFrame = 65536;
    bool feed(const uint8_t* data, size_t length) {
        if (failed || length > 131104 || buffer.size() + length > 131104) return fail();
        if (!length) return true;
        buffer.insert(buffer.end(), data, data + length);
        return true;
    }
    // 1: complete frame, 0: need more bytes, -1: protocol error.
    int next(RelayFrame& frame) {
        if (failed) return -1;
        if (buffer.size() < 2) return 0;
        bool fin = (buffer[0] & 0x80) != 0;
        uint8_t opcode = buffer[0] & 15;
        if ((buffer[0] & 0x70) != 0 || (buffer[1] & 0x80) != 0) return bad();
        size_t header = 2;
        uint64_t length = buffer[1] & 127;
        if (length == 126) {
            if (buffer.size() < 4) return 0;
            header = 4; length = (uint64_t(buffer[2]) << 8) | buffer[3];
            if (length < 126) return bad();
        } else if (length == 127) {
            if (buffer.size() < 10) return 0;
            header = 10; length = 0;
            for (size_t i = 2; i < 10; i++) length = (length << 8) | buffer[i];
            if (length < 65536 || length >> 63) return bad();
        }
        if (length > maxFrame) return bad();
        if (opcode >= 8) {
            if (!fin || length > 125 || (opcode != 8 && opcode != 9 && opcode != 10) || (opcode == 8 && length == 1)) return bad();
        } else if (opcode == 2) {
            if (fragmented) return bad();
        } else if (opcode != 0 || !fragmented) return bad();
        if (buffer.size() < header + length) return 0;
        if (opcode < 8) {
            if (opcode == 2) messageSize = 0;
            messageSize += length;
            if (messageSize > 1048576) return bad();
            fragmented = !fin;
        }
        frame.opcode = opcode;
        frame.payload.assign(buffer.begin() + header, buffer.begin() + header + size_t(length));
        buffer.erase(buffer.begin(), buffer.begin() + header + size_t(length));
        return 1;
    }
private:
    bool fail() { failed = true; buffer.clear(); return false; }
    int bad() { fail(); return -1; }
    bool failed = false;
    bool fragmented = false;
    uint64_t messageSize = 0;
    std::vector<uint8_t> buffer;
};

inline std::vector<uint8_t> relayMaskedFrame(uint8_t opcode, const uint8_t* data, size_t length, const std::array<uint8_t,4>& mask) {
    if (length > RelayFrameDecoder::maxFrame || (opcode >= 8 && length > 125)) return {};
    std::vector<uint8_t> result;
    result.reserve(length + 14);
    result.push_back(0x80 | opcode);
    if (length < 126) result.push_back(0x80 | uint8_t(length));
    else if (length <= 65535) { result.push_back(0x80 | 126); result.push_back(uint8_t(length >> 8)); result.push_back(uint8_t(length)); }
    else { result.push_back(0x80 | 127); for (int i = 7; i >= 0; i--) result.push_back(uint8_t(uint64_t(length) >> (8*i))); }
    result.insert(result.end(), mask.begin(), mask.end());
    for (size_t i = 0; i < length; i++) result.push_back(data[i] ^ mask[i&3]);
    return result;
}

}
