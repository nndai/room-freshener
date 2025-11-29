#ifndef C0NFIG_H
#define C0NFIG_H

#include <Arduino.h>
#include <LittleFS.h>
#include <ArduinoJson.h>

enum ModeConnect {
    WEBSOCKET,
    MQTT,
};

struct WiFiConfig {
    ModeConnect mode;
    String ssidAp;
    String passwordAp;
    String ssid;
    String password;
};

#define VERSION "1.0"

#define WEBSOCKET_PORT 82
#define SPRAY_PIN 12
#define LED_PIN 2
#define BUTTON_PIN 14
#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5

#define PATH_FOLDER_DATA "/datas/"
#define PATH_FOLDER_LOG "/logs/"
#define PATH_FOLDER_SPRAY_LOG "/logs/spray/"
#define PATH_FILENAME_SPRAY_TASKS "/datas/sprayTasks.bin"
#define PATH_FILENAME_WIFI_CONFIG "/datas/wifiConfig.json"
#define PATH_FILENAME_SPRAY_DATA_TOTAL "/datas/sprayDataTotal.bin"

#define WIFIAP_SSID_DEFAULT "MÁY XỊT PHÒNG"
#define WIFIAP_PASSWORD_DEFAULT "123456788"

#define TLS_MQTT_URL "..."
#define TLS_MQTT_PORT 8883
#define TLS_MQTT_USERNAME "..."
#define TLS_MQTT_PASSWORD "..."
#define MQTT_TOPIC_RECEIVE "..."
#define MQTT_TOPIC_SEND "..."

#define MQTT_MAX_PACKET_SIZE_OVERRIDE 3000

#define _TASK_SLEEP_ON_IDLE_RUN
#define _TASK_STD_FUNCTION

#define TIME_ZONE +7 // Vietnam time zone UTC+7
#define CONVERT_TO_LOCAL_TIME(dt) ((dt) + TimeSpan(TIME_ZONE * 3600))

void loadWiFiConfig(WiFiConfig& wifiConfig) {
    Serial.print("Loading WiFi configuration...");
    File file = LittleFS.open(PATH_FILENAME_WIFI_CONFIG, "r");
    if (!file) {
        Serial.println(" ---> No WiFi config file found. Using default settings.");
        return;
    }

    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, file);
    if (error) {
        Serial.print(" ---> Failed to deserializeJson WiFi config file: ");
        Serial.println(error.f_str());
        file.close();
        return;
    }

    wifiConfig.mode = doc["mode"] | WEBSOCKET;
    wifiConfig.ssidAp = doc["ssidAp"] | WIFIAP_SSID_DEFAULT;
    wifiConfig.passwordAp = doc["passwordAp"] | WIFIAP_PASSWORD_DEFAULT;
    wifiConfig.ssid = doc["ssid"] | "";
    wifiConfig.password = doc["password"] | "";

    if (wifiConfig.mode != WEBSOCKET && wifiConfig.mode != MQTT) {
        wifiConfig.mode = WEBSOCKET;
    }
    Serial.println(" ---> success.");
    file.close();
}

void saveWiFiConfig(const WiFiConfig& wifiConfig) {
    Serial.print("Saving WiFi configuration...");
    JsonDocument doc;
    doc["mode"] = wifiConfig.mode;
    doc["ssidAp"] = wifiConfig.ssidAp;
    doc["passwordAp"] = wifiConfig.passwordAp;
    doc["ssid"] = wifiConfig.ssid;
    doc["password"] = wifiConfig.password;
    File file = LittleFS.open(PATH_FILENAME_WIFI_CONFIG, "w");
    serializeJson(doc, file);
    file.close();
    Serial.println(" ---> success.");
}

#endif