#include "../receiver/src/BeaconParser.h"
#include <cassert>
#include <cstdio>
#include <random>
#include <vector>
int main() {
    WifiMetadata out;
    assert(!parseWifiMetadata(nullptr, 0, out));
    std::vector<uint8_t> beacon(46, 0); beacon[0] = 0x80; beacon[34] = 0x10; beacon[36] = 0; beacon[37] = 4;
    memcpy(beacon.data() + 38, "TEST", 4);
    assert(parseWifiMetadata(beacon.data(), beacon.size(), out)); assert(out.beacon && out.privacy && !strcmp(out.name, "TEST"));
    beacon[37] = 100; assert(!parseWifiMetadata(beacon.data(), beacon.size(), out));
    beacon[0] = 0x40; assert(parseWifiMetadata(beacon.data(), beacon.size(), out)); assert(!out.beacon && !out.name[0]);
    beacon[0] = 0x08; assert(!parseWifiMetadata(beacon.data(), beacon.size(), out));
    std::vector<uint8_t> probe(24,0);probe[0]=0x40;
    const uint8_t body[]={0,0,2,0,12,0,127,0,221,7,0x50,0x6f,0x9a,0x16,3,1,3,45,0,191,0,221,7,0,0x50,0xf2,8,0,0,0};
    probe.insert(probe.end(),body,body+sizeof(body));probe.insert(probe.end(),4,0);
    assert(parseWifiMetadata(probe.data(),probe.size(),out)&&out.wildcard&&out.fingerprint);
    probe[35]^=1;assert(parseWifiMetadata(probe.data(),probe.size(),out)&&out.wildcard&&!out.fingerprint);
    std::mt19937 random(9818);
    for (int n = 0; n < 100000; n++) {
        std::vector<uint8_t> frame(random() % 1024); for (auto& b : frame) b = random();
        bool ok = parseWifiMetadata(frame.data(), frame.size(), out);
        if (ok) { assert(out.name[32] == 0); assert(frame.size() >= 24); }
    }
    puts("Beacon bounds, probe privacy, frame type and 100000 malformed-frame checks passed");
}
