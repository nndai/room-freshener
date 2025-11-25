#ifndef C0NFIG_H
#define C0NFIG_H

#include <Arduino.h>
#include <LittleFS.h>
#include <ArduinoJson.h>

enum ModeConnect {
    WEBSOCKET,
    BLYNK,
};

struct WiFiConfig {
    ModeConnect mode;
    String ssidAp;
    String passwordAp;
    String ssid;
    String password;
};

struct SprayDataTotal {
    uint32_t totalSpraysCount;
    uint32_t totalSprayDuration; // in seconds
};

#define WEBSOCKET_PORT 82
#define SPRAY_PIN 12
#define LED_PIN 2
#define BUTTON_PIN 14
#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5

#define FOLDER_DATA "/data/"
#define FOLDER_LOG "/logs/"
#define FOLDER_SPRAY_LOG FOLDER_LOG "spray/"
#define FILENAME_SPRAY_TASKS "sprayTasks.bin"
#define FILENAME_WIFI_CONFIG "wifiConfig.json"
#define FILENAME_SPRAY_DATA_TOTAL "sprayDataTotal.bin"

#define WIFIAP_SSID_DEFAULT "MÁY XỊT PHÒNG"
#define WIFIAP_PASSWORD_DEFAULT "123456788"

#define BLYNK_TEMPLATE_ID ""
#define BLYNK_TEMPLATE_NAME ""
#define BLYNK_AUTH_TOKEN ""
#define NO_GLOBAL_BLYNK

#define _TASK_SLEEP_ON_IDLE_RUN
#define _TASK_STD_FUNCTION

void loadWiFiConfig(WiFiConfig& wifiConfig) {
    File file = LittleFS.open(FOLDER_DATA FILENAME_WIFI_CONFIG, "r");
    if (!file) {
        Serial.println("No WiFi config file found. Using default settings.");
        return;
    }

    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, file);
    if (error) {
        Serial.print(F("Failed to read WiFi config file: "));
        Serial.println(error.f_str());
        file.close();
        return;
    }

    wifiConfig.mode = doc["mode"] | WEBSOCKET;
    wifiConfig.ssidAp = doc["ssidAp"] | WIFIAP_SSID_DEFAULT;
    wifiConfig.passwordAp = doc["passwordAp"] | WIFIAP_PASSWORD_DEFAULT;
    wifiConfig.ssid = doc["ssid"] | "";
    wifiConfig.password = doc["password"] | "";

    file.close();
}

void saveWiFiConfig(const WiFiConfig& wifiConfig) {
    JsonDocument doc;
    doc["mode"] = wifiConfig.mode;
    doc["ssidAp"] = wifiConfig.ssidAp;
    doc["passwordAp"] = wifiConfig.passwordAp;
    doc["ssid"] = wifiConfig.ssid;
    doc["password"] = wifiConfig.password;
    File file = LittleFS.open(FOLDER_DATA FILENAME_WIFI_CONFIG, "w");
    serializeJson(doc, file);
    file.close();
}

void loadSprayDataTotal(SprayDataTotal& sprayDataTotal) {
    File file = LittleFS.open(FOLDER_DATA FILENAME_SPRAY_DATA_TOTAL, "r");
    if (!file) {
        Serial.println("No spray data total file found. Using default values.");
        return;
    }

    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, file);
    if (error) {
        Serial.print(F("Failed to read spray data total file: "));
        Serial.println(error.f_str());
        file.close();
        return;
    }

    sprayDataTotal.totalSpraysCount = doc["totalSpraysCount"] | 0;
    sprayDataTotal.totalSprayDuration = doc["totalSprayDuration"] | 0;

    file.close();
}

void saveSprayDataTotal(const SprayDataTotal& sprayDataTotal) {
    JsonDocument doc;
    doc["totalSpraysCount"] = sprayDataTotal.totalSpraysCount;
    doc["totalSprayDuration"] = sprayDataTotal.totalSprayDuration;
    File file = LittleFS.open(FOLDER_DATA FILENAME_SPRAY_DATA_TOTAL, "w");
    serializeJson(doc, file);
    file.close();
}


#endif