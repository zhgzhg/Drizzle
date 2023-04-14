Drizzle
=======
Declarative package manager plugin for Arduino IDE [v1.8.x](https://github.com/arduino/Arduino).

<img alt="Drizzle" src="https://raw.githubusercontent.com/zhgzhg/Drizzle/master/drizzle-logo.svg?sanitize=true" />

Drizzle installs libraries, platforms, boards, picks board-specific settings, and even installs other Arduino IDE tools with a single click.
__The preferences are described into the sketch's comments or in a separate JSON file__. Switching between Arduino projects with different
settings or compiling them on different environments has never been easier! 

![Build Distribution Jar](https://github.com/zhgzhg/Drizzle/workflows/Build%20Distribution%20Jar/badge.svg)

Drizzle operates on top of Arduino IDE's library and board manager. It enables them to provide components, which now can be described in
text form. Because the only change in the sketch is the addition of a few _comments_ or editing _a separate JSON file_, the sketch will
remain compatible with Arduino IDEs which don't have Drizzle installed.


How to Use
----------

1. Create a comment inside the main file of your Arduino sketch (preferably at the beginning).

2. Use markers like `@DependsOn`, `@BoardManager`, `@BoardSettings`, `@Board`, `@Preferences`, and `@ArduinoTool` to describe the sketch's
requirements.
   
   1. You may use some of the __Tools -> Drizzle -> Auto-generate ... marker__ UI options to let Drizzle fill them as a C-style comment at the
      beginning of your main sketch file, based on IDE's current settings. Depending on the environment the result might require
      adjustments.

3. Save your sketch.


<img alt="Drizzle Sample Usage GIF" src="https://raw.githubusercontent.com/zhgzhg/Drizzle/master/drizzle-sample-usage.gif" />


An example:

```
/*
 * Hello World Arduino sketch.
 *
 * This sketch depends on the libraries and settings described below. You can either manually download
 * them via the Library Manager and select the needed options by hand, or do all of the above
 * via the "Tools -> Drizzle -> Apply Markers" menu command.
 *
 * @BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json
 * @Board esp8266::esp8266::NodeMCU 1.0 (ESP-12E Module)
 * @BoardSettings esp8266::NodeMCU 1.0 (ESP-12E Module)::Flash Frequency->40MHz||Flash Mode->QIO
 *
 * @DependsOn Arduino_CRC32::1.0.0
 * @DependsOn Arduino Cloud Provider Examples::*
 * @DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)
 *
 * @Preferences esp8266::esp8266::NodeMCU 1.0 (ESP-12E Module)::compiler.cpp.extra_flags=-std=gnu++14 -DTEST123
 *
 * @ArduinoTool Drizzle::(<0.16.0)::https://github.com/zhgzhg/Drizzle/releases/download/0.16.0/drizzle-0.16.0-dist.zip
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
Like that the code self-explains its dependencies, so anyone interested in compiling it can do that with 2 clicks:

* __Tools -> Drizzle -> Apply Markers__ menu
* __Verify / Upload__ button

Using and combining Drizzle's markers is always optional.


Alternative Usage
-----------------

The __drizzle.json__ file can be used instead of the annotated comments in the main sketch file, if it's placed next to it, for describing dependencies and settings. In this case __drizzle.json__ becomes the only settings/dependencies source, and any Drizzle markers found in the source code will be ignored.

An example for __drizzle.json__ can read in the [CLI Extras](#cli-extras) section.
 

How to Install
--------------

1. Download the "-dist" ZIP file from the [Releases](https://github.com/zhgzhg/Drizzle/releases)' assets.
2. Unzip it inside **Arduino's installation directory** / **tools**
3. Restart Arduino IDE


How to Update
-------------

When Arduino IDE is opened, Drizzle will automatically check for newer version, and will offer a semi-automatic update process - once downloaded, the IDE will be closed. The next time you open it, the latest version of Drizzle will be fully installed.

For a manual update follow the steps in [How to Install](#how-to-Install) .


Supported Markers
-----------------

* __@BoardManager__ _platform_name_::_platform_version_[::_url_of_the_board_manager_json_]
  * Install platforms a.k.a boards
  * __Respects the first valid marker that's found. The rest will be ignored.__
  * Examples:
    * `@BoardManager Arduino AVR Boards::1.6.4`
    * `@BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json`
    * `@BoardManager esp32::>1.0.3::https://dl.espressif.com/dl/package_esp32_index.json`
    * `@BoardManager Raspberry Pi Pico/RP2040::~1.6::https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json`


* __@Board__ [_provider_package_name_::]_platform_name_::_board_id_ | _board_name_
  * Selects the id/name of the target board. **The ID matching is with priority!**
  * __Respects the first valid marker that's found. The rest will be ignored.__
  * __Not specifying provider package name would make Drizzle to attempt resolving it automatically.__
  * Examples:
    * `@Board arduino::Arduino AVR Boards::Arduino Nano`
    * `@Board esp8266::NodeMCU 1.0 (ESP-12E Module)`
    * `@Board esp32::ESP32 Wrover Module`
    * `@Board esp32::esp32::esp32wrover`
    * `@Board rp2040::Raspberry Pi Pico/RP2040::Raspberry Pi Pico`


* __@DependsOn__ _library_name_::(_library_version_ | _library_uri_)
  * Downloads libraries or installs local ones.
  * __More__ than 1 marker can be used. 
  * _library_name_ must be unique. In case of duplicated library names, the first one will be respected.
  * The version can be either specified directly or via conditional expressions.
    * Full list of the supported expressions at: https://github.com/npm/node-semver#ranges
    * In case _library_uri_ is specified instead of _library_version_:
      * _library_name_ must be unique, but is not required to precisely match the actual library name
      * HTTP or HTTPS protocol is supported for .ZIP files directly specified in the URI, or GIT repositories
      * HTTP(S) URLs for git can end with `#tag_or_commit_or_branch_name` reference - see the examples below
      * file:/// prefix can be used to point to local directory containing the library, or to a concrete .ZIP file
  * To achieve better control any transitive dependencies won't be automatically installed, but will be listed in the logs.
  * Examples:
    * `@DependsOn Arduino_CRC32::1.0.0`
    * `@DependsOn Arduino Cloud Provider Examples::*`
    * `@DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)`
  * Examples with files:
    * `@DependsOn Local BMP280_DEV::file:///C:/Users/John/Desktop/BMP280_DEV-1.0.15.zip`
    * `@DependsOn Local BMP280_DEV::file:///C:/Users/John/Desktop/BMP280_DEV_DIRECTORY`
    * `@DependsOn Github's BMP280_DEV::https://github.com/MartinL1/BMP280_DEV/archive/master.zip`
    * `@DependsOn Github's BMP280_DEV::https://github.com/MartinL1/BMP280_DEV.git`
    * `@DependsOn Github's BMP280_DEV::https://github.com/MartinL1/BMP280_DEV.git#V1.0.21`


* __@BoardSettings__ _platform_name_::_board_id_ | _board_name_::_menu_path_[->_option_][||_another_menu_path_->option...]
  * Clicks on the UI options provided by a particular board (and platform)
  * Board ID or name can be used. **Board ID matching is attempted first.**
  * __More__ than 1 marker can be used.
  * To match all platforms and/or board ids or board names a * can be used
  * To describe the path to the particular option -> can be used
  * To separate multiple menu paths || can be used
  * Menu matching is case-sensitive
  * Menu matching will be performed in the order of definition and __will stop immediately once a match is found__. Always define the
    concrete rules in the beginning, and the less concrete at the end.  
  * Examples:
    * `@BoardSettings esp32::esp32wrover::Flash Frequency->40MHz`
    * `@BoardSettings esp32::ESP32 Wrover Module::Flash Frequency->40MHz`
    * `@BoardSettings esp32::*::Flash Frequency->40MHz||PSRAM->Disabled`
    * `@BoardSettings *::*::Upload Speed->115200`

* __@Preferences__ [_provider_package_name_::]_platform_name_::_board_id_ | _board_name_::definition=value[||another_definition=another_value...]
  * At compile time automatically appends arbitrary runtime preference definitions
  * Board ID or name can be used. **Board ID matching is attempted first.**
  * __More__ than 1 marker can be used.
  * All the markers are attempted to be applied while matching against the current selected board.
  * To match all provider package, platforms and/or board ids or board names a * can be used
  * The matching is case-sensitive
  * The matching will be performed in the order of definition, from left to right, and __will stop immediately once a match is found__.
Always define the concrete rules in the beginning, and the less concrete at the end.
  * Examples:
    * `@Preferences esp32::esp32::esp32wrover::compiler.cpp.extra_flags=-std=gnu++17 -DMYDEF||compiler.c.extra_flags=-DMYDEFINITION2`
    * `@Preferences *::esp32::*::compiler.cpp.extra_flags=-std=gnu++14`
    * `@Preferences *::*::compiler.cpp.extra_flags=-std=gnu++14`

* __@ArduinoTool__ _tool_name_::_version_::_tool_zip_url_
  * Installs an Arduino tool from __ZIP__ archive into the IDE. The tool is unzipped inside Arduino IDE's installation directory / tools,
    and will require restarting the IDE in order to be activated.
  * This marker __works a bit different compared to _@DependsOn___. It performs __supervising work rather than actual dependency version
    management__. It should be used with caution as it has the potential of installing unwanted software. 
  * The _version_ __field serves as a condition__ when _tool_zip_url_ to be installed.
  * Version information of the already installed tool is obtained from the meta information inside the JAR file. If it doesn't contain
    _MANIFEST.MF_ or it's lacking an _Implementation-Version_ entry no actions will be taken. Otherwise, Drizzle will attempt installing the
    tool from _tool_zip_url_.
  * The _tool_name_ must match the name of the directory containing the actual tool. It has to be unique. In the case of several duplicating
    names the first one will be respected.
  * Examples:
    * `@ArduinoTool Drizzle::(<0.16.0)::https://github.com/zhgzhg/Drizzle/releases/download/0.16.0/drizzle-0.16.0-dist.zip`
    * `@ArduinoTool Drizzle::*::file:///C:/Users/John/Drizzle/drizzle.zip`
    * `@ArduinoTool EspExceptionDecoder::(<=1.0.0)::https://github.com/me-no-dev/EspExceptionDecoder/releases/download/2.0.2/EspExceptionDecoder-2.0.2.zip`


CLI Extras
----------

Drizzle offers CLI parsing of any Arduino sketch file, printing the recognized marker settings in JSON format. The reverse operation, where
from JSON file Drizzle markers will be produced is also supported.

For e.g. `java -jar drizzle-0.16.0-with-deps.jar --parse hello-world.ino` will produce:

```
{
  "board_manager": {
    "platform": "esp8266",
    "version": "^2.6.3",
    "url": "https://arduino.esp8266.com/stable/package_esp8266com_index.json"
  },
  "board": {
    "providerPackage": "esp8266",
    "platform": "esp8266",
    "name": "NodeMCU 1.0 (ESP-12E Module)"
  },
  "board_settings": [
    {
      "board": {
        "platform": "esp8266",
        "name": "NodeMCU 1.0 (ESP-12E Module)"
      },
      "clickable_options": [
        [
          "Flash Frequency",
          "40MHz"
        ],
        [
          "Flash Mode",
          "QIO"
        ]
      ]
    }
  ],
  "libraries": {
    "BMP280_DEV": {
      "version": "(>= 1.0.8 && < 1.0.16)",
      "arduinoCliFmt": "BMP280_DEV@1.0.8"
    },
    "Arduino_CRC32": {
      "version": "1.0.0",
      "arduinoCliFmt": "Arduino_CRC32@1.0.0"
    },
    "Arduino Cloud Provider Examples": {
      "version": "*",
      "arduinoCliFmt": "Arduino Cloud Provider Examples"
    }
  },
  "preferences": [
    {
      "board": {
        "providerPackage": "esp8266",
        "platform": "esp8266",
        "name": "NodeMCU 1.0 (ESP-12E Module)"
      },
      "preferences": {
        "compiler.cpp.extra_flags": "-std=gnu++14 -DTEST123"
      }
    }
  ],
  "arduino_ide_tools": {
    "Drizzle": {
      "version": "(<0.16.0)",
      "url": "https://github.com/zhgzhg/Drizzle/releases/download/0.16.0/drizzle-0.16.0-dist.zip"
    }
  }
}
```

Executing on the above JSON `java -jar drizzle-0.16.0-with-deps.jar --rev-parse hello-world.json` will produce:

```
@BoardManager esp8266::^2.6.3::https://arduino.esp8266.com/stable/package_esp8266com_index.json
@Board esp8266::esp8266::NodeMCU 1.0 (ESP-12E Module)
@BoardSettings esp8266::NodeMCU 1.0 (ESP-12E Module)::Flash Frequency->40MHz||Flash Mode->QIO
@DependsOn BMP280_DEV::(>= 1.0.8 && < 1.0.16)
@DependsOn Arduino_CRC32::1.0.0
@DependsOn Arduino Cloud Provider Examples::*
@Preferences esp8266::esp8266::NodeMCU 1.0 (ESP-12E Module)::compiler.cpp.extra_flags=-std=gnu++14 -DTEST123
@ArduinoTool Drizzle::(<0.16.0)::https://github.com/zhgzhg/Drizzle/releases/download/0.16.0/drizzle-0.16.0-dist.zip
```


How To Compile
--------------

1. `mvnw com.googlecode.maven-download-plugin:download-maven-plugin:wget@install-arduino-libs`
2. `mvnw clean package`
