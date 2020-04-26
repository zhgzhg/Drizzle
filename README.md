Drizzle
=======
A Lightweight dependency helper for Arduino IDE.

By clicking on "Tools / Bulk Resolve Marked Dependencies" it will download the libraries and platform specified into the comments of your
sketch file.

![Build Fat Jar](https://github.com/zhgzhg/Drizzle/workflows/Build%20Fat%20Jar/badge.svg)

How to Use
----------

```
/*
 * Hello World Arduino sketch.
 *
 * This sketch depends on the libraries described below. You can either manually download them by hand via the
 * Library Manager or use the "Bulk Resolve Marked Dependencies" command from the Tools menu.
 *
 * @BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json
 * @Board esp8266::NodeMCU 1.0 (ESP-12E Module)
 *
 * @DependsOn Arduino_CRC32::1.0.0
 * @DependsOn Arduino Cloud Provider Examples::*
 * @DependsOn BMP280_DEV::(>= 1.0.8 || < 1.0.15)
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
```
Create a comment inside the main file of your Arduino sketch. Preferably at the beginning.

Use markers like "@DependsOn" to describe sketch's requirements.
 

How To Install
--------------

1. Download the "-dist" ZIP file from the [Releases](https://github.com/zhgzhg/Drizzle/releases).
2. Unzip it inside **Arduino's installation directory / tools**
3. Restart Arduino IDE

Supported Markers
-----------------

* __@BoardManager__ _platform_name_::_platform_version_[::_url_of_the_board_manager_json_]
  * Install platforms a.k.a boards
  * __Respects the first valid marker that's found. The rest will be ignored.__
  * Examples:
    * `@BoardManager Arduino AVR Boards::1.6.4`
    * `@BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json`
    * `@BoardManager esp32::>1.0.3::https://dl.espressif.com/dl/package_esp32_index.json`
* __@Board__ _platform_name_::_board_name_
  * Selects the name of the target board
  * __Respects the first valid marker that's found. The rest will be ignored.__
  * Examples:
    * `@Board Arduino AVR Boards::Arduino Nano`
    * `@Board esp8266::NodeMCU 1.0 (ESP-12E Module)`
    * `@Board esp32::ESP32 Dev Module`
* __@DependsOn__ _library_name_::_library_version_
  * Downloads libraries
  * __More__ than 1 marker can be used
  * The version can be either specified directly or via conditional expressions.
    * Full list of the supported expressions at: https://github.com/npm/node-semver#ranges
  * Examples
    * `@DependsOn Arduino_CRC32::1.0.0`
    * `@DependsOn Arduino Cloud Provider Examples::*`
    * `@DependsOn BMP280_DEV::(>= 1.0.8 || < 1.0.15)`


How To Compile
--------------

1. `mvnw com.googlecode.maven-download-plugin:download-maven-plugin:wget@install-arduino-libs`
2. `mvnw clean package`