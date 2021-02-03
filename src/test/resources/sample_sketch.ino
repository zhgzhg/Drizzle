/*
 * Hello World Arduino sketch.
 *
 * This sketch depends on the libraries described below. You can either manually download them by hand via the
 * Library Manager or use the "Bulk Resolve Marked Dependencies" command from the Tools menu.
 *
 * @BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json
 * @Board esp8266::NodeMCU 1.0 (ESP-12E Module)
 * @BoardSettings esp8266::NodeMCU 1.0 (ESP-12E Module)::Flash Frequency->40MHz||Flash Mode->QIO
 *
 * @DependsOn Arduino_CRC32::1.0.0
 * @DependsOn Arduino Cloud Provider Examples::*
 * @DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)
 *
 * @ArduinoTool Drizzle::(<=0.2)::file:///somewhere.zip
 * @ArduinoTool Drizzl3::(<=0.3)::file:///somewhere.zip
 */

// Your sample code follows below just as usual

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_BUILTIN, HIGH);
  delay(1000);
  digitalWrite(LED_BUILTIN, LOW);
  delay(1000);
}