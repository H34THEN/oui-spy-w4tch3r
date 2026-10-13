#include <Arduino.h>
#include <ArduinoJson.h>
#include <NimBLEDevice.h>
#include <esp_wifi.h>
#include <nvs_flash.h>
#include "BeaconParser.h"

// No Wi-Fi station, AP, association, scan/probe requests, or BLE connections.
// Only received metadata is queued; payloads/handshakes are not retained.
struct Event { char radio[8]; char address[18]; char name[80]; char security[16]; char service[4][40]; uint8_t serviceCount; uint16_t manufacturer; bool hasManufacturer; bool wildcard; bool fingerprint; int rssi; int frequency; };
static QueueHandle_t events;
static volatile bool observing = false;
static uint32_t keepalive = 0, lastHop = 0;
static uint8_t channel = 1;
static NimBLEScan* ble;
static void macString(char* out, const uint8_t* mac) {
    snprintf(out, 18, "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}
static void receiveWifi(void* buffer, wifi_promiscuous_pkt_type_t type) {
    if (!observing || type != WIFI_PKT_MGMT) return;
    auto* packet = static_cast<wifi_promiscuous_pkt_t*>(buffer);
    const uint8_t* p = packet->payload; const size_t length = packet->rx_ctrl.sig_len;
    WifiMetadata metadata;
    if (!parseWifiMetadata(p, length, metadata)) return;
    Event event = {}; strlcpy(event.radio, "Wi-Fi", sizeof(event.radio)); macString(event.address, metadata.address);
    event.wildcard=metadata.wildcard;event.fingerprint=metadata.fingerprint;
    event.rssi = packet->rx_ctrl.rssi;
    uint8_t ch = packet->rx_ctrl.channel; event.frequency = ch == 14 ? 2484 : 2407 + ch * 5;
    strlcpy(event.name, metadata.name, sizeof(event.name));
    if (metadata.beacon) strlcpy(event.security, metadata.privacy ? "[PRIVACY]" : "[OPEN]", sizeof(event.security));
    xQueueSend(events, &event, 0);
}
class Adverts : public NimBLEAdvertisedDeviceCallbacks {
    void onResult(NimBLEAdvertisedDevice* device) override {
        if (!observing) return;
        Event event = {}; strlcpy(event.radio, "BLE", sizeof(event.radio));
        strlcpy(event.address, device->getAddress().toString().c_str(), sizeof(event.address));
        if (device->haveName()) strlcpy(event.name, device->getName().c_str(), sizeof(event.name));
        event.serviceCount=min(uint8_t(4),device->getServiceUUIDCount());
        for(uint8_t i=0;i<event.serviceCount;i++)strlcpy(event.service[i],device->getServiceUUID(i).toString().c_str(),sizeof(event.service[i]));
        if (device->haveManufacturerData()) { auto data = device->getManufacturerData(); if (data.size() >= 2) { event.hasManufacturer = true; event.manufacturer = uint8_t(data[0]) | (uint16_t(uint8_t(data[1])) << 8); } }
        event.rssi = device->getRSSI(); xQueueSend(events, &event, 0);
    }
};
static void stopReception() { observing = false; esp_wifi_set_promiscuous(false); if (ble->isScanning()) ble->stop(); xQueueReset(events); }
void setup() {
    Serial.begin(115200); events = xQueueCreate(64, sizeof(Event));
    if (!events) { while (true) delay(1000); }
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) { nvs_flash_erase(); err = nvs_flash_init(); }
    ESP_ERROR_CHECK(err);
    wifi_init_config_t config = WIFI_INIT_CONFIG_DEFAULT(); ESP_ERROR_CHECK(esp_wifi_init(&config));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM)); ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL)); ESP_ERROR_CHECK(esp_wifi_start());
    wifi_promiscuous_filter_t filter = {}; filter.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT;
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_filter(&filter)); ESP_ERROR_CHECK(esp_wifi_set_promiscuous_rx_cb(receiveWifi));
    NimBLEDevice::init(""); ble = NimBLEDevice::getScan();
    ble->setMaxResults(0); ble->setActiveScan(false); // passive advertisements only: no scan requests
    ble->setAdvertisedDeviceCallbacks(new Adverts(), true); ble->setInterval(160); ble->setWindow(80);
}
void loop() {
    static char command[16]; static size_t used = 0;
    while (Serial.available()) {
        char c = Serial.read();
        if (c == '\n') {
            command[used] = 0; used = 0;
            if (!strcmp(command, "HELLO")) { stopReception(); Serial.println("{\"persephone_passive\":1}"); }
            else if (!strcmp(command, "START")) {
                stopReception(); keepalive = millis(); channel = 1;
                ESP_ERROR_CHECK(esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE)); observing = true;
                ESP_ERROR_CHECK(esp_wifi_set_promiscuous(true)); ble->start(0, static_cast<void (*)(NimBLEScanResults)>(nullptr), false);
            } else if (!strcmp(command, "KEEP")) keepalive = millis();
            else if (!strcmp(command, "STOP")) stopReception();
        } else if (c != '\r') { if (used < sizeof(command) - 1) command[used++] = c; else used = 0; }
    }
    if (observing && (millis() - keepalive > 3000 || !Serial)) stopReception();
    if (observing && millis() - lastHop >= 250) {
        channel = channel % 11 + 1; esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE); lastHop = millis();
    }
    Event event;
    // Cap output work per loop so USB keepalives remain responsive in crowded venues.
    for (int n = 0; observing && n < 8 && xQueueReceive(events, &event, 0) == pdTRUE; n++) {
        StaticJsonDocument<1024> row;
        row["radio"] = event.radio; row["address"] = event.address; row["name"] = event.name;
        row["rssi"] = event.rssi; row["frequency"] = event.frequency; row["security"] = event.security;
        if(event.serviceCount){auto services=row.createNestedArray("services");for(uint8_t i=0;i<event.serviceCount;i++)services.add(event.service[i]);}
        if (event.hasManufacturer) row.createNestedArray("manufacturer_ids").add(event.manufacturer);
        row["wildcard_probe"]=event.wildcard;row["ie_fingerprint"]=event.fingerprint;
        row["connectable"] = false; serializeJson(row, Serial); Serial.println();
    }
    delay(2);
}
