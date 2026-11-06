#include "SessionAuthData.h"
#include <cstdio>
#include <cstdlib>
#include <cassert>
using namespace ghostsession;
static Data fixture(){Data d;d.user=1234567;d.mainDc=2;d.apiId=28804573;Dc dc;dc.id=2;dc.authorized=true;dc.key.resize(256);for(size_t i=0;i<256;i++)dc.key[i]=(uint8_t)i;Address a;a.ip="127.0.0.1";a.port=443;dc.addresses.push_back(a);Salt s;s.since=100;s.until=200;s.value=123;dc.salts.push_back(s);d.dcs.push_back(dc);return d;}
static bool valid(const std::vector<uint8_t>& v){Data d;return decode(v.data(),v.size(),d);}
int main(int argc,char** argv){
    auto d=fixture();auto bytes=encode(d);assert(valid(bytes));
    for(size_t i=0;i<bytes.size();i++){auto shortFile=std::vector<uint8_t>(bytes.begin(),bytes.begin()+i);assert(!valid(shortFile));}
    auto tail=bytes;tail.push_back(0);assert(!valid(tail));
    auto bad=fixture();bad.dcs[0].key.resize(255);assert(!valid(encode(bad)));
    bad=fixture();bad.mainDc=4;assert(!valid(encode(bad)));
    bad=fixture();bad.dcs.push_back(bad.dcs[0]);assert(!valid(encode(bad)));
    bad=fixture();bad.dcs[0].addresses[0].ip="not-an-ip.example";assert(!valid(encode(bad)));
    bad=fixture();bad.dcs[0].authorized=false;assert(!valid(encode(bad)));
    bad=fixture();bad.dcs[0].salts[0].until=99;assert(!valid(encode(bad)));
    bad=fixture();bad.dcs[0].addresses[0].group=4;assert(!valid(encode(bad)));
    bad=fixture();bad.dcs[0].addresses[0].port=65536;assert(!valid(encode(bad)));
    bad=fixture();bad.apiId=0;assert(!valid(encode(bad)));
    auto huge=bytes;huge[32]=255;huge[33]=255;assert(!valid(huge));
    if(argc>1){FILE* out=fopen(argv[1],"wb");assert(out);assert(fwrite(bytes.data(),1,bytes.size(),out)==bytes.size());fclose(out);}
    std::printf("PASS: native authorization codec, all truncations, duplicates, key/address/salt bounds; synthetic fixture only\n");
}
