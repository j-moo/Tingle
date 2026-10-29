#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <bluefruit.h>

// Name shown in the phone's BLE device list.
const char DEVICE_NAME[] = "Tingle-Keyring";

// Create a BLE UART service for sending and receiving bytes.
BLEUart bleUart;

// Remember the previous connection states.
bool monitorWasOpen = false;
bool uartWasReady = false;

void setup()
{
    // Start USB serial communication with the PC.
    Serial.begin(115200);

    // Start BLE and set the device name.
    Bluefruit.begin();
    Bluefruit.setName(DEVICE_NAME);

    // Start the BLE UART service.
    bleUart.begin();

    // Advertise the UART service.
    Bluefruit.Advertising.addFlags(
        BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addService(bleUart);

    // Put the device name in the scan response packet.
    Bluefruit.ScanResponse.addName();

    // Restart advertising after the phone disconnects.
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(32, 244);
    Bluefruit.Advertising.setFastTimeout(30);

    // Keep advertising until a phone connects.
    Bluefruit.Advertising.start(0);
}

void loop()
{
    bool monitorOpen = (bool)Serial;

    // The phone must connect and enable UART notifications.
    bool uartReady =
        Bluefruit.connected() && bleUart.notifyEnabled();

    // Show instructions whenever the Serial Monitor is opened.
    if (monitorOpen && !monitorWasOpen)
    {
        Serial.println();
        Serial.println("USB serial ready.");
        Serial.print("BLE device name: ");
        Serial.println(DEVICE_NAME);

        if (uartReady)
        {
            Serial.println("[BLE] UART ready.");
        }
        else
        {
            Serial.println("[BLE] Waiting for phone UART.");
        }
    }
    else if (monitorOpen && uartReady != uartWasReady)
    {
        Serial.println();

        if (uartReady)
        {
            Serial.println("[BLE] UART ready.");
        }
        else
        {
            Serial.println("[BLE] UART not ready.");
        }
    }

    monitorWasOpen = monitorOpen;
    uartWasReady = uartReady;

    // Forward bytes from the phone to the PC.
    while (bleUart.available() > 0)
    {
        int receivedByte = bleUart.read();

        if (monitorOpen)
        {
            Serial.write((uint8_t)receivedByte);
        }
    }

    // Collect up to 20 bytes sent from the PC.
    if (Serial.available() > 0)
    {
        uint8_t buffer[20];
        int length = 0;

        while (Serial.available() > 0 && length < 20)
        {
            buffer[length] = (uint8_t)Serial.read();
            length++;
        }

        // Forward the collected bytes to the phone.
        if (uartReady)
        {
            size_t sent = bleUart.write(buffer, length);

            if (sent != (size_t)length)
            {
                Serial.println();
                Serial.println("[BLE] Send failed. Try again.");
            }
        }
        else
        {
            Serial.println("[BLE] Open UART on the phone first.");
        }
    }
}
