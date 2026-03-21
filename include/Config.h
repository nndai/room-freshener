#ifndef C0NFIG_H
#define C0NFIG_H

#include <Arduino.h>
#include <LittleFS.h>
#include <ArduinoJson.h>

enum ModeConnect {
    WEBSOCKET,
    MQTT,
};

struct SystemConfig {
    ModeConnect mode;
    String ssidAp;
    String passwordAp;
    String ssid;
    String password;
    uint32_t hwButtonDurationMs;
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
#define WIFIAP_PASSWORD_DEFAULT "12345678"

#define TLS_MQTT_URL "0bab47da6e4b4af7a0a58dcce1c70db2.s1.eu.hivemq.cloud"
#define TLS_MQTT_PORT 8883
#define TLS_MQTT_USERNAME "dai05"
#define TLS_MQTT_PASSWORD "Daicredentials1"
#define MQTT_TOPIC_RECEIVE "roomFreshener/command"
#define MQTT_TOPIC_SEND "roomFreshener/response"

#define MQTT_MAX_PACKET_SIZE_OVERRIDE 3000
#define MQTT_SOCKET_TIMEOUT_OVERRIDE 7

#define _TASK_SLEEP_ON_IDLE_RUN
#define _TASK_STD_FUNCTION
#define _TASK_SCHEDULING_OPTIONS

#define TIME_ZONE +7 // Vietnam time zone UTC+7
#define CONVERT_TO_LOCAL_TIME(dt) ((dt) + TimeSpan(TIME_ZONE * 3600))

void loadSystemConfig(SystemConfig& systemConfig) {
    Serial.print("Loading System configuration...");
    File file = LittleFS.open(PATH_FILENAME_WIFI_CONFIG, "r");
    if (!file) {
        Serial.println(" ---> No config file found. Using default settings.");
        return;
    }

    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, file);
    if (error) {
        Serial.print(" ---> Failed to deserializeJson config file: ");
        Serial.println(error.f_str());
        file.close();
        return;
    }

    systemConfig.mode = doc["mode"] | WEBSOCKET;
    systemConfig.ssidAp = doc["ssidAp"] | WIFIAP_SSID_DEFAULT;
    systemConfig.passwordAp = doc["passwordAp"] | WIFIAP_PASSWORD_DEFAULT;
    systemConfig.ssid = doc["ssid"] | "";
    systemConfig.password = doc["password"] | "";
    systemConfig.hwButtonDurationMs = doc["hwButtonDurationMs"] | 1000;

    if (systemConfig.mode != WEBSOCKET && systemConfig.mode != MQTT) {
        systemConfig.mode = WEBSOCKET;
    }
    Serial.println(" ---> success.");
    Serial.print("Mode: "); Serial.println(systemConfig.mode == WEBSOCKET ? "WEBSOCKET" : "MQTT");
    Serial.print("SSID: "); Serial.println(systemConfig.ssid);

    Serial.print("Password: "); Serial.println(systemConfig.password.length() > 0 ? "******" : "(empty)");
    Serial.print("AP SSID: "); Serial.println(systemConfig.ssidAp);
    Serial.print("AP Password: "); Serial.println(systemConfig.passwordAp);
    file.close();
}

void saveSystemConfig(const SystemConfig& systemConfig) {
    Serial.print("Saving System configuration...");
    JsonDocument doc;
    doc["mode"] = systemConfig.mode;
    doc["ssidAp"] = systemConfig.ssidAp;
    doc["passwordAp"] = systemConfig.passwordAp;
    doc["ssid"] = systemConfig.ssid;
    doc["password"] = systemConfig.password;
    doc["hwButtonDurationMs"] = systemConfig.hwButtonDurationMs;
    File file = LittleFS.open(PATH_FILENAME_WIFI_CONFIG, "w");
    serializeJson(doc, file);
    file.close();
    Serial.println(" ---> success.");
}

#endif