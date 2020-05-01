Drizzle
=======
A lightweight dependency helper for Arduino IDE.

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
 * @DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)
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


* __@DependsOn__ _library_name_::(_library_version_ | _library_uri_)
  * Downloads libraries or installs local ones
  * __More__ than 1 marker can be used. 
  * _library_name_ must be unique. In case of duplicated library names, the last one will be respected.
  * The version can be either specified directly or via conditional expressions.
    * Full list of the supported expressions at: https://github.com/npm/node-semver#ranges
    * In case _library_uri_ is specified instead of _library_version_:
      * _library_name_ must be unique, but is not required to precisely match the actual library name
      * HTTP or HTTPS protocol is supported for .ZIP files directly specified in the URI
      * file:/// prefix can be used to point to local directory containing the library, or to a concrete .ZIP file
  * Examples:
    * `@DependsOn Arduino_CRC32::1.0.0`
    * `@DependsOn Arduino Cloud Provider Examples::*`
    * `@DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)`
  * Examples with files:
    * `@DependsOn Local BMP280_DEV::file:///C:/Users/John/Desktop/BMP280_DEV-1.0.15.zip`
    * `@DependsOn Local BMP280_DEV::file:///C:/Users/John/Desktop/BMP280_DEV_DIRECTORY`
    * `@DependsOn Github's BMP280_DEV::https://github.com/MartinL1/BMP280_DEV/archive/master.zip`

CLI Extras
----------

In addition, Drizzle offers CLI parsing of any Arduino sketch file, printing the recognized marker settings in JSON format and the reverse
operation too.

For e.g. `java -jar drizzle-0.4.0.jar --parse hello-world.ino` will produce:

```
{
    "board_manager": {
        "platform": "esp8266",
        "version": "^2.6.3",
        "url": "https://arduino.esp8266.com/stable/package_esp8266com_index.json"
    },
    "board": {
        "platform": "esp8266",
        "name": "NodeMCU 1.0 (ESP-12E Module)"
    },
    "libraries": {
        "BMP280_DEV": "(>= 1.0.8 && < 1.0.16)",
        "Arduino_CRC32": "1.0.0",
        "Arduino Cloud Provider Examples": "*"
    }
}
```

Executing `java -jar drizzle-0.4.0.jar --rev-parse hello-world.json` will produce:

```
@BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json
@Board esp8266::NodeMCU 1.0 (ESP-12E Module)
@DependsOn Arduino_CRC32::1.0.0
@DependsOn Arduino Cloud Provider Examples::*
@DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)
```


How To Compile
--------------

1. `mvnw com.googlecode.maven-download-plugin:download-maven-plugin:wget@install-arduino-libs`
2. `mvnw clean package`