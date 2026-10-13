#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

struct WifiMetadata { uint8_t address[6]; char name[33]; bool beacon; bool privacy; bool wildcard; bool fingerprint; };
// Public ordered IE signature bytes: CYD-Flock-You / colonelpanichacks research.
// Independent bounded matcher; no raw payload is retained.
inline bool communityProbeSignature(const uint8_t* p,size_t size) {
    const uint8_t tags[]={0,2,12,127,221,45,191,221};
    const uint8_t vendorA[]={0x50,0x6f,0x9a,0x16,0x03,0x01,0x03},vendorB[]={0x00,0x50,0xf2,0x08,0,0,0};
    size_t offset=0;
    for(size_t index=0;index<8;index++) {
        if(offset+2>size||p[offset]!=tags[index]) return false;
        size_t length=p[offset+1];if(length>size-offset-2) return false;
        if(index==0&&length!=0) return false;
        if(index==4||index==7) {if(length!=7||memcmp(p+offset+2,index==4?vendorA:vendorB,7)) return false;}
        offset+=2+length;
    }
    return offset==size;
}
// No frame payload escapes this parser. Probe SSIDs are omitted.
inline bool parseWifiMetadata(const uint8_t* p, size_t length, WifiMetadata& out) {
    if (!p || length < 24 || (p[0] & 0x0f) != 0) return false;
    uint8_t subtype = (p[0] >> 4) & 15;
    if (subtype != 8 && subtype != 4) return false;
    out = {}; memcpy(out.address, p + 10, 6); out.beacon = subtype == 8;
    if (!out.beacon) {
        for(size_t i=24;i+2<=length;) {size_t n=p[i+1];if(n>length-i-2)break;if(p[i]==0){out.wildcard=n==0;break;}i+=2+n;}
        if(out.wildcard) out.fingerprint=communityProbeSignature(p+24,length-24)||(length>=28&&communityProbeSignature(p+24,length-28));
        return true;
    }
    if (length < 40) return false; // fixed beacon header plus FCS
    out.privacy = (p[34] & 0x10) != 0;
    for (size_t i = 36; i + 2 <= length - 4;) {
        size_t n = p[i + 1]; if (i + 2 + n > length - 4) return false;
        if (p[i] == 0) { if (n > 32) return false; memcpy(out.name, p + i + 2, n); out.name[n] = 0; return true; }
        i += 2 + n;
    }
    return true;
}
