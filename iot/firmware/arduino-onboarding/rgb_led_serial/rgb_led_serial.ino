#include <Adafruit_TinyUSB.h>

// XIAO nRF52840: onboard LEDs are active LOW.
// Select 115200 baud in the Serial Monitor.

const int LED_PINS[] = {LED_RED, LED_GREEN, LED_BLUE};
const char* const LED_COLORS[] = {"RED", "GREEN", "BLUE"};

const int LED_COUNT = sizeof(LED_PINS) / sizeof(LED_PINS[0]);
const int DELAY_MS = 500;

void setup() {
  Serial.begin(115200);

  // Configure all LED pins and turn them off.
  for (int ledPin : LED_PINS) {
    pinMode(ledPin, OUTPUT);
    digitalWrite(ledPin, HIGH);
  }
}

void loop() {
  // Blink each LED and report every state change.
  for (int i = 0; i < LED_COUNT; i++) {
    digitalWrite(LED_PINS[i], LOW); // Turn the LED on.

    // Print only when the USB serial connection is ready.
    if (Serial) {
      Serial.print(LED_COLORS[i]);
      Serial.println(": ON");
    }

    delay(DELAY_MS);

    digitalWrite(LED_PINS[i], HIGH);  // Turn the LED off.

    if (Serial) {
      Serial.print(LED_COLORS[i]);
      Serial.println(": OFF");
    }

    delay(DELAY_MS);
  }
}
