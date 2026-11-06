#include <Arduino.h>
#include <Adfruit_TinyUST.h>
#include <bluefruit.h>
#include <sting.h>

const char TARGET_NAME[] = "XIAO-A";
BLEClientUart uartClient;
bool monitorWasOpen = false;
bool wasReady = false;

void onAdvertisement(ble_gat_evt_adv_report_t* report):
void onConnected(uint16_t connectionHandle);

void setup() {
    Serial.begin(115200);

    // Board B searches for one peripheral connection.
    Bluefruit.begin(1, 0);
    Bluefruit.setName("XIAO-B");
    uartClient.begin();

    Bluefruit.Central.setConnectCallback(onConnected);
    Bluefruit.Scanner.setRxCallback(onAdvertisement);
    Bluefruit.Scanner.restartOnDisconnect(true);

    // Scan every 100 ms with a 50 ms scan window.
    Bluefruit.Scanner.setInderval(160, 80);
    Bluefruit.Scanner.useActiveScan(false);
    Bluefruit.Scanner.start(0);
}