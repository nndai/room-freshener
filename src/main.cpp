#include <Arduino.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <RTClib.h>
#include <OneButton.h>
#include "LedController.h"
#include "SprayScheduler.h"

#define _TASK_SLEEP_ON_IDLE_RUN
#define _TASK_STD_FUNCTION
#include <TaskScheduler.h>


#define WEBSOCKET_PORT 82
#define SPRAY_PIN 12
#define LED_PIN 2
#define BUTTON_PIN 14
#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5

#define FILENAME_SPRAY_LOG "sprayLog.txt"

const char* ssid = "ESP8266_AP";
const char* password = "123456788";

WebSocketsServer websocket(WEBSOCKET_PORT);

RTC_DS1307 rtc;
SprayController sprayController(SPRAY_PIN);
SprayScheduler sprayScheduler(&sprayController, &rtc);

LedController led(LED_PIN, true);
OneButton button(BUTTON_PIN, true);

Scheduler mainScheduler;

//======================== Prototypes ========================
void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length);


//======================== Tasks =============================
Task taskUpdateSprayScheduler(2000, TASK_FOREVER, []() {
    sprayScheduler.update();
    }, & mainScheduler, true);

Task taskLoopWebsockets(10, TASK_FOREVER, []() {
    websocket.loop();
    }, & mainScheduler, true);

Task taskUpdateLed(50, TASK_FOREVER, []() {
    led.update();
    }, & mainScheduler, true);


//========================= Setup & Loop ======================
void setup() {
    led.on();
    Serial.begin(115200);
    LittleFS.begin();
    LittleFS.format();

    WiFi.mode(WIFI_AP);
    WiFi.softAP(ssid, password);

    Serial.println("Access Point đã được kích hoạt!");
    Serial.print("SSID: "); Serial.println(ssid);
    Serial.print("IPAP: "); Serial.println(WiFi.softAPIP());

    websocket.begin();
    websocket.onEvent(webSocketEvent);

    led.blink(500);
}

void loop() {
    mainScheduler.execute();
}

//=============================================================

void handleMessage(uint8_t num, uint8_t* payload) {
    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, payload);
    if (error) {
        Serial.print(F("deserializeJson() failed: "));
        Serial.println(error.f_str());
        return;
    }

    String command = doc["command"] | "";
    if (command == "") {
        Serial.println("No command specified.");
        return;
    }

    /**
     * Xử lý lệnh phun sương ngay lập tức
     * example:
     * received JSON:
     * {
     *   "command": "sprayNow",
     *   "duration": 5000
     * }
     *
     * response: none
     */
    if (command == "sprayNow") {
        uint32_t duration = doc["duration"] | 0;
        sprayController.on(duration);
        Serial.printf("Spraying for %u ms\n", duration);
    }

    /**
     * Xử lý lệnh thêm lịch phun sương
     * example:
     * received JSON:
     * {
     *   "command": "addTaskSpray",
     *   "hour": 14,
     *   "minute": 30,
     *   "weekday": 1,
     *   "duration": 5000,
     *   "enabled": true
     * }
     * response JSON:
     * {
     *   "command": "addTaskSprayResponse",
     *   "status": 1,
     *   "taskId": 1
     *  }
     */
    else if (command == "addTaskSpray") {
        uint8_t hour = doc["hour"] | 255;
        uint8_t minute = doc["minute"] | 255;
        uint8_t weekday = doc["weekday"] | 255;
        uint32_t duration = doc["duration"] | 0;
        bool enabled = doc["enabled"] | false;

        uint8_t taskId = sprayScheduler.addTask(0, hour, minute, weekday, duration, enabled);

        JsonDocument responseDoc;
        responseDoc["command"] = "addTaskSprayResponse";
        if (taskId == 0) {
            Serial.println("Failed to add spray task.");
            responseDoc["status"] = false;
            responseDoc["message"] = "Failed to add task.";
        }
        else if (taskId == 255) {
            Serial.println("Spray task list is full.");
            responseDoc["status"] = false;
            responseDoc["message"] = "Task list is full.";
        }
        else {
            Serial.printf("Added spray task with ID %u\n", taskId);
            responseDoc["status"] = true;
            responseDoc["taskId"] = taskId;
            responseDoc["message"] = "success.";
        }
        String jsonStr;
        serializeJson(doc, jsonStr);
        websocket.sendTXT(num, jsonStr);
    }

    /**
     * Xử lý lệnh xóa lịch phun sương
     * example:
     * received JSON:
     * {
     *   "command": "removeTaskSpray",
     *   "taskId": 1
     * }
     * response JSON:
     * {
     *   "command": "removeTaskSprayResponse",
     *   "status": 1,
     *   "message":"success"
     * }
     */
    else if (command == "removeTaskSpray") {
        uint8_t taskId = doc["taskId"] | 0;

        JsonDocument responseDoc;
        responseDoc["command"] = "removeTaskSprayResponse";

        if (taskId == 0 || taskId == 255) {
            Serial.println("Invalid task ID.");
            responseDoc["status"] = false;
            responseDoc["message"] = "Invalid task ID.";
        }
        else {
            if (sprayScheduler.removeTask(taskId)) {
                Serial.printf("Removed spray task with ID %u\n", taskId);
                responseDoc["status"] = true;
                responseDoc["message"] = "success.";
            }
            else {
                Serial.printf("Spray task with ID %u not found.\n", taskId);
                responseDoc["status"] = false;
                responseDoc["message"] = "Task not found.";
            }
        }
        String jsonStr;
        serializeJson(doc, jsonStr);
        websocket.sendTXT(num, jsonStr);
    }

    /**
     * Xử lý lệnh đặt trạng thái kích hoạt của lịch phun sương
     * example:
     * received JSON:
     * {
     *   "command": "setTaskEnabled",
     *   "task_id": 1,
     *   "enabled": true
     * }
     *
     * response JSON:
     * {
     *   "command": "setTaskEnabledResponse",
     *   "status": 1,
     *   "message":"success"
     * }
     */
    else if (command == "setTaskEnabled") {
        uint8_t taskId = doc["task_id"] | 0;
        bool enabled = doc["enabled"] | false;

        JsonDocument responseDoc;
        responseDoc["command"] = "setTaskEnabledResponse";

        if (taskId == 0 || taskId == 255) {
            responseDoc["status"] = false;
            responseDoc["message"] = "Invalid task ID.";
        }
        else {
            if (sprayScheduler.setTaskEnabled(taskId, enabled)) {
                Serial.printf("Set spray task ID %u enabled=%s\n", taskId, enabled ? "true" : "false");
                responseDoc["status"] = true;
                responseDoc["message"] = "success.";
            }
            else {
                Serial.printf("Spray task with ID %u not found.\n", taskId);
                responseDoc["status"] = true;
                responseDoc["message"] = "Task not found.";
            }
        }
        String jsonStr;
        serializeJson(doc, jsonStr);
        websocket.sendTXT(num, jsonStr);
    }

    else if( command == "editTaskSpray") {
        uint8_t taskId = doc["taskId"] | 255;
        uint8_t hour = doc["hour"] | 255;
        uint8_t minute = doc["minute"] | 255;
        uint8_t weekday = doc["weekday"] | 255;
        uint32_t duration = doc["duration"] | 0;
        bool enabled = doc["enabled"] | false;

        JsonDocument responseDoc;
        responseDoc["command"] = "editTaskSprayResponse";

        if (sprayScheduler.editTask({ taskId, hour, minute, weekday, enabled, duration })) {
            Serial.printf("Edited spray task with ID %u\n", taskId);
            responseDoc["status"] = true;
            responseDoc["message"] = "success.";
        }
        else {
            Serial.printf("Spray task with ID %u not found.\n", taskId);
            responseDoc["status"] = false;
            responseDoc["message"] = "Task not found.";
        }
        String jsonStr;
        serializeJson(doc, jsonStr);
        websocket.sendTXT(num, jsonStr);
    }

    /**
     * Xử lý lệnh lấy thời gian hiện tại từ RTC
     * example:
     * received JSON:
     * {
     *   "command": "getTime"
     * }
     *
     * response JSON:
     * {
     *   "command": "getTimeResponse",
     *   "timestamp": 1633024800
     * }
     */
    else if (command == "getTime") {
        uint32_t currentTime = rtc.now().unixtime();
        Serial.printf("Current RTC time: %u\n", currentTime);
        String responseStr = "{\"command\":\"getTimeResponse\",\"timestamp\":" + String(currentTime) + "}";
        websocket.sendTXT(num, responseStr.c_str());
    }

    /**
     * Xử lý lệnh lấy tất cả lịch phun sương
     * example:
     * received JSON:
     * {
     *   "command": "getAllTaskSpray"
     * }
     *
     * response JSON:
     * {
     *   "command": "getAllTasksSprayResponse",
     *   "countask": 2,
     *   "sizeTask": 12,
     *   "dataTask": "BASE64_ENCODED_DATA_HERE"
     * }
     */
    else if (command == "getAllTaskSpray") {
        JsonDocument tasksJson = sprayScheduler.createTasksJson();
        tasksJson["command"] = "getAllTasksSprayResponse";
        String jsonStr;
        serializeJson(tasksJson, jsonStr);
        websocket.sendTXT(num, jsonStr);
        Serial.println("Sent all spray tasks JSON.");
    }

    else if (command == "setTime") {
        uint32_t timestamp = doc["timestamp"] | 0;
        rtc.adjust(DateTime(timestamp));
        Serial.printf("RTC time set to %u\n", timestamp);
        String responseStr = "{\"command\":\"setTimeResponse\",\"status\":true,\"message\":\"RTC time updated. Current time: " 
            + String(rtc.now().unixtime()) + "\"}";
        websocket.sendTXT(num, responseStr.c_str());
    }
    else {
        Serial.printf("Unknown command: %s\n", command.c_str());
        websocket.sendTXT(num, "{\"command\":\"unknownCommandResponse\",\"status\":false,\"message\":\"Unknown command.\"}");
    }
}

void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
    case WStype_CONNECTED:
        Serial.printf("[%u] Connected!\n", num);
        break;

    case WStype_DISCONNECTED:
        Serial.printf("[%u] Disconnected!\n", num);
        break;

    case WStype_TEXT:
        Serial.printf("[%u] Received Text: %s\n", num, payload);
        handleMessage(num, payload);
        break;

    case WStype_BIN:
        Serial.printf("[%u] Received binary data of length: %u\n", num, length);
        break;

    default:
        break;
    }
}
