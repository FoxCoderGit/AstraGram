#ifndef GHOST_SESSION_AUTH_DATA_H
#define GHOST_SESSION_AUTH_DATA_H
#include <cstdint>
#include <vector>
#include <string>
#include <set>
#include <cstring>
#include <utility>

// A bounded, versioned authorization-only record. No history, secret chats or session IDs.
namespace ghostsession {
inline void wipe(std::vector<uint8_t>& data) { volatile uint8_t* p=data.data(); for(size_t n=0;n<data.size();++n)p[n]=0; }
struct Address { uint32_t group=0,port=0,flags=0; std::string ip,secret; };
struct Salt { int32_t since=0,until=0; int64_t value=0; };
struct Dc {
    uint32_t id=0; bool authorized=false;
    std::vector<Address> addresses; std::vector<uint8_t> key; std::vector<Salt> salts;
    ~Dc(){wipe(key);}
};
struct Data { int64_t user=0; uint32_t mainDc=0; int32_t timeDifference=0,apiId=0; bool test=false; std::vector<Dc> dcs; };
struct Reader {
    const uint8_t* p;size_t size,pos=0;bool ok=true;
    uint32_t u32(){if(!ok||size-pos<4){ok=false;return 0;}uint32_t v=0;for(int i=0;i<4;i++)v|=uint32_t(p[pos++])<<(i*8);return v;}
    uint64_t u64(){uint64_t a=u32(),b=u32();return a|(b<<32);}
    std::vector<uint8_t> bytes(size_t max){uint32_t n=u32();if(!ok||n>max||size-pos<n){ok=false;return {}; }std::vector<uint8_t> v(p+pos,p+pos+n);pos+=n;return v;}
    std::string string(size_t max){auto v=bytes(max);return std::string(v.begin(),v.end());}
};
inline bool decode(const uint8_t* bytes,size_t size,Data& out){
    if(size<32||size>65536)return false;
    Reader r{bytes,size};
    if(r.u32()!=0x47534131||r.u32()!=1)return false;
    out.user=(int64_t)r.u64();out.mainDc=r.u32();out.timeDifference=(int32_t)r.u32();uint32_t test=r.u32();out.apiId=(int32_t)r.u32();uint32_t count=r.u32();out.test=test==1;
    if(out.user<=0||out.apiId<=0||test>1||count==0||count>16)return false;
    std::set<uint32_t> ids;bool main=false;
    for(uint32_t n=0;n<count&&r.ok;n++){
        Dc d;d.id=r.u32();uint32_t authorized=r.u32(),addresses=r.u32();d.authorized=authorized==1;
        if(d.id==0||d.id>1000||!ids.insert(d.id).second||authorized>1||addresses>128)return false;
        for(uint32_t a=0;a<addresses&&r.ok;a++){
            Address address;address.group=r.u32();address.ip=r.string(255);address.port=r.u32();address.flags=r.u32();address.secret=r.string(512);
            if(address.group>3||address.port==0||address.port>65535||address.ip.empty()||address.ip.find_first_not_of("0123456789abcdefABCDEF:.")!=std::string::npos)return false;
            d.addresses.push_back(address);
        }
        d.key=r.bytes(256);uint32_t salts=r.u32();
        if((!d.key.empty()&&d.key.size()!=256)||salts>64)return false;
        for(uint32_t s=0;s<salts&&r.ok;s++){Salt salt;salt.since=(int32_t)r.u32();salt.until=(int32_t)r.u32();salt.value=(int64_t)r.u64();if(salt.until<=salt.since)return false;d.salts.push_back(salt);}
        if(d.id==out.mainDc){main=d.key.size()==256&&d.authorized&&!d.addresses.empty();}
        out.dcs.push_back(d);
    }
    return r.ok&&r.pos==size&&main;
}
struct Writer {
    std::vector<uint8_t> data;
    void u32(uint32_t v){for(int i=0;i<4;i++)data.push_back((uint8_t)(v>>(i*8)));}
    void u64(uint64_t v){u32((uint32_t)v);u32((uint32_t)(v>>32));}
    void bytes(const uint8_t* p,size_t n){u32((uint32_t)n);if(n)data.insert(data.end(),p,p+n);}
    void string(const std::string& s){bytes((const uint8_t*)s.data(),s.size());}
};
inline std::vector<uint8_t> encode(const Data& v){
    Writer w;w.u32(0x47534131);w.u32(1);w.u64(v.user);w.u32(v.mainDc);w.u32(v.timeDifference);w.u32(v.test?1:0);w.u32(v.apiId);w.u32(v.dcs.size());
    for(const Dc& d:v.dcs){w.u32(d.id);w.u32(d.authorized?1:0);w.u32(d.addresses.size());for(const Address& a:d.addresses){w.u32(a.group);w.string(a.ip);w.u32(a.port);w.u32(a.flags);w.string(a.secret);}w.bytes(d.key.data(),d.key.size());w.u32(d.salts.size());for(const Salt& s:d.salts){w.u32(s.since);w.u32(s.until);w.u64(s.value);}}
    return std::move(w.data);
}
}
#endif
