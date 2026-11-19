#include "../../TMessagesProj/jni/tgnet/AstraRelayPolicy.h"
#include "../../TMessagesProj/jni/tgnet/AstraWebSocket.h"
#include <cassert>
#include <cstdio>
#include <vector>

using namespace astragram;

static std::vector<uint8_t> frame(uint8_t opcode,const std::vector<uint8_t>& payload,bool fin=true) {
    std::vector<uint8_t> bytes{uint8_t(opcode|(fin?128:0))};
    if(payload.size()<126)bytes.push_back(uint8_t(payload.size()));
    else if(payload.size()<65536){bytes.push_back(126);bytes.push_back(uint8_t(payload.size()>>8));bytes.push_back(uint8_t(payload.size()));}
    else{bytes.push_back(127);for(int i=7;i>=0;i--)bytes.push_back(uint8_t(uint64_t(payload.size())>>(8*i)));}
    bytes.insert(bytes.end(),payload.begin(),payload.end());return bytes;
}

int main() {
    {
        RelayPolicy p;
        assert(p.route(0)==RelayRoute::WaitingForAccess);
        assert(p.route(29999)==RelayRoute::WaitingForAccess);
        assert(p.route(30000)==RelayRoute::Direct);
        assert(p.retryAt()==3630000);
        assert(p.route(3629999)==RelayRoute::Direct);
        assert(p.route(3630000)==RelayRoute::WaitingForAccess);
    }
    {
        RelayPolicy p;p.access(RelayPermission::Allowed,1000,RelayPolicy::permissionMs);
        assert(p.route(1000)==RelayRoute::Relay);p.attempting(1000);
        assert(p.route(30999)==RelayRoute::Relay);assert(p.attemptRemaining(30999)==1);
        assert(p.route(31000)==RelayRoute::Direct);assert(p.retryAt()==3631000);
        p.access(RelayPermission::Allowed,50000,RelayPolicy::permissionMs);
        assert(p.route(50000)==RelayRoute::Direct);
        assert(p.route(3630999)==RelayRoute::Direct);assert(p.route(3631000)==RelayRoute::Relay);
        p.attempting(3631000);p.connected();assert(p.retryAt()==0);assert(p.route(3632000)==RelayRoute::Relay);
    }
    {
        RelayPolicy p;p.access(RelayPermission::Allowed,0,RelayPolicy::permissionMs);p.attempting(0);
        p.access(RelayPermission::Denied,1000,RelayPolicy::permissionMs);
        p.restoreCooldown(1000,RelayPolicy::retryMs);p.attempting(2000);p.connected();
        assert(p.route(3601000)==RelayRoute::Direct);
        assert(p.route(86400999)==RelayRoute::Direct);
        assert(p.route(86401000)==RelayRoute::WaitingForAccess);
        p.access(RelayPermission::Allowed,86401001,RelayPolicy::permissionMs);
        assert(p.route(86401001)==RelayRoute::Relay);
    }
    {
        RelayPolicy p;p.access(RelayPermission::Allowed,0,RelayPolicy::permissionMs);p.attempting(0);p.networkLost();
        assert(p.route(60000)==RelayRoute::Relay);p.attempting(60000);
        assert(p.route(89999)==RelayRoute::Relay);assert(p.route(90000)==RelayRoute::Direct);
        auto retry=p.retryAt();p.networkLost();assert(p.retryAt()==retry);
    }
    {
        RelayPolicy p;p.access(RelayPermission::Allowed,0,1000);
        assert(p.route(999)==RelayRoute::Relay);assert(p.route(1000)==RelayRoute::WaitingForAccess);
        p.access(RelayPermission::Allowed,1001,1000);assert(p.route(1001)==RelayRoute::Relay);
        p.restoreCooldown(1001,10000);assert(p.route(2000)==RelayRoute::Direct);assert(p.route(11001)==RelayRoute::WaitingForAccess);
    }
    for(size_t size:{size_t(0),size_t(1),size_t(125),size_t(126),size_t(65535),size_t(65536)}) {
        std::vector<uint8_t> payload(size);for(size_t i=0;i<size;i++)payload[i]=uint8_t(i*17);
        auto encoded=frame(2,payload);RelayFrameDecoder decoder;RelayFrame decoded;
        size_t offset=0;while(offset<encoded.size()){
            size_t chunk=std::min<size_t>(137,encoded.size()-offset);
            assert(decoder.feed(encoded.data()+offset,chunk));offset+=chunk;
            int status=decoder.next(decoded);assert(status==(offset==encoded.size()?1:0));
        }
        assert(decoded.opcode==2&&decoded.payload==payload);
        auto masked=relayMaskedFrame(2,payload.data(),payload.size(),{1,2,3,4});
        size_t header=size<126?2:size<=65535?4:10;assert(masked.size()==payload.size()+header+4);
        for(size_t i=0;i<size;i++)assert(uint8_t(masked[header+4+i]^masked[header+(i&3)])==payload[i]);
        RelayFrameDecoder invalid;assert(invalid.feed(masked.data(),masked.size()));assert(invalid.next(decoded)==-1);
    }
    {
        RelayFrameDecoder decoder;RelayFrame decoded;
        for(auto item:{frame(2,{1,2},false),frame(9,{7}),frame(0,{3,4})}){assert(decoder.feed(item.data(),item.size()));assert(decoder.next(decoded)==1);}
    }
    for(auto malformed:std::vector<std::vector<uint8_t>>{{0x81,0},{0xc2,0},{0x82,0x80},{0x80,0},{0x09,0},{0x88,1,0},{0x82,126,0,1},{0x82,127,0x80,0,0,0,0,0,0,0}}){
        RelayFrameDecoder decoder;RelayFrame decoded;assert(decoder.feed(malformed.data(),malformed.size()));assert(decoder.next(decoded)==-1);
    }
    std::puts("PASS: relay 30-second recovery, hourly retry, 24-hour administrative denial, expiry, network changes and bounded WebSocket binary framing.");
}
