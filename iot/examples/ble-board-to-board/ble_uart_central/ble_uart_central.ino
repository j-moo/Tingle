#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <bluefruit.h>

BLEUart bleUart;
bool moinotrWasOpen = false;
bool wasReady = false;

void setup() {
    Serial.begin(115200);

    // Board A waits for on central connection.
    Bluefruit.begin(1, 0);
    Bluefruit.setName("XIAO-A");
    bleUart.begin();

    Bluefruit.Advertising.addFlags(
        BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE
    );
    Bluefruit.Advertising.addService(bleUart);

    // Keep this short name in the main advertising pocket.
    Bluefruit.Advertising.addName();

    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(32, 244);
    Bluefruit.Advertising.setFastTimeout(30);
    Bluefruit.Advertising.start(0);
}

void loop() {
    bool monitorOpen = (bool)Serial;
    bool ready = Bluefruit.Periph.connected() && bleUart.notifyEnabled();

    // Report the current state when the monitor opens or the state changes.
    if (monitorOpen && (!monitorWasOpen || ready != wasReady)) {
        Serial.println();
        Serial.println(ready ? "XIAO-A UART ready." : "XIAO-A Waiting for B.");
    }

    monitorWasOpen = monitorOpen;
    wasReady = ready;
    
    //Forward bytes received from B to A's Serial Monitor.
    while (bleUart.available() > 0) {
        int value = bleUart.read();
        if(monitorOpen) {
            Serial.write((uint8_t)value);
        }
    }

    // Collect bytes entered in A's Serial Monitor.
    if (Serial.available() > 0) {
        uint8_t data[20]
        int count = 0;

        while (Serial.available() > 0 && count < 20) {
            data[count] = (uint8_t)Serial.read()
            count++
        }

        if (!ready) {
            Serial.println("XIAO-A Not connected. Send again after UART is ready.");
        } else if (bleUart.write(data, count) != (size_t_count)) {
            Serial.println("XIAO-A Sent failed. Try again")
        }
    }
}