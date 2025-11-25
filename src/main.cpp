#include <Arduino.h>
#include "Config.h"
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <RTClib.h>
#include <OneButton.h>
#include "LedController.h"
#include "SprayScheduler.h"
#include <TaskScheduler.h>
#include <BlynkSimpleEsp8266.h>
#include <PubSubClient.h>


WiFiConfig wifiConfig = {
    WEBSOCKET,
    WIFIAP_SSID_DEFAULT,
    WIFIAP_PASSWORD_DEFAULT,
    "",
    ""
};

WebSocketsServer* websocket = nullptr;
WiFiClient* wifiBlynkClient = nullptr;
BlynkArduinoClient* blynkTransport = nullptr;
BlynkWifi* blynk = nullptr;

WiFiClientSecure* wifiMqttClient = nullptr;
PubSubClient* mqttClient = nullptr;

RTC_DS1307 rtc;
SprayController sprayController(SPRAY_PIN, &rtc, PATH_FOLDER_SPRAY_LOG, PATH_FILENAME_SPRAY_DATA_TOTAL);
SprayScheduler sprayScheduler(&sprayController, &rtc, PATH_FILENAME_SPRAY_TASKS);

LedController led(LED_PIN, true);
OneButton button(BUTTON_PIN, true);

Scheduler mainScheduler;

//======================== Prototypes ========================
void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length);
void setupTask();
void setupWebSocket();
void setupBlynk();
void setupMqtt();
void setupConnection();
void handleMessage(uint8_t num, uint8_t* payload);


//======================== Tasks =============================
Task* taskUpdateSprayScheduler;
Task* taskUpdateLed;
Task* taskButtonCheck;
Task* taskConnectToBlynk;
Task* taskConnectToMqtt;
Task* taskConnectWiFi;
Task* taskLoopConnection;



//========================= Setup & Loop ======================
void setup() {
    led.on();
    Serial.begin(115200);
    LittleFS.begin();
    LittleFS.format(); // Uncomment this line to format LittleFS on first run

    setupTask();
    loadWiFiConfig(wifiConfig);

    sprayScheduler.load();

    setupConnection();

    led.blink(2, 500, 2000);
}

void loop() {
    mainScheduler.execute();
}

//=============================================================


void setupTask() {
    taskUpdateSprayScheduler = new Task(2000, TASK_FOREVER, []() {
        sprayScheduler.update();
        }, &mainScheduler, true);

    taskUpdateLed = new Task(50, TASK_FOREVER, []() {
        led.update();
        }, &mainScheduler, true);

    taskButtonCheck = new Task(10, TASK_FOREVER, []() {
        button.tick();
        }, &mainScheduler, true);


    taskConnectToBlynk = new Task(200, TASK_FOREVER, []() {
        if (blynk && blynk->connect(1000)) {
            Serial.println("Connected to Blynk Cloud!");
            taskConnectToBlynk->disable();
            taskLoopConnection->enable();
        }
        }, &mainScheduler, false);

    taskConnectToMqtt = new Task(2000, TASK_FOREVER, []() {
        String clientID = "ESPClient-";
        clientID += String(random(0xffff), HEX);
        if (mqttClient && mqttClient->connect(clientID.c_str(), TLS_MQTT_USERNAME, TLS_MQTT_PASSWORD)) {
            Serial.println("Connected to MQTT Broker!");
            mqttClient->subscribe(MQTT_TOPIC_COMMAND);
            taskConnectToMqtt->disable();
            taskLoopConnection->enable();
        }
        }, &mainScheduler, false);

    taskConnectWiFi = new Task(200, TASK_FOREVER, []() {
        if (WiFi.status() == WL_CONNECTED) {
            Serial.println("Connected to WiFi!");
            Serial.print("IP address: ");
            Serial.println(WiFi.localIP());
            Serial.println("Connecting to Blynk Cloud...");

            if (wifiConfig.mode == BLYNK) {
                setupBlynk();
            }
            else if (wifiConfig.mode == MQTT) {
                setupMqtt();
            }
            taskConnectWiFi->disable();
        }
        }, &mainScheduler, false);

    taskLoopConnection = new Task(10, TASK_FOREVER, []() {
        if (websocket) {
            websocket->loop();
        }
        else if (mqttClient) {
            mqttClient->loop();
            if (!mqttClient->connected()) {
                taskConnectToMqtt->enable();
                taskLoopConnection->disable();
            }
        }
        else if (blynk) {
            blynk->run();
        }
        }, &mainScheduler, false);

}


void setupWebSocket() {
    if (blynk) {
        blynk->disconnect();
        delete blynk;
        if (blynkTransport)
            delete blynkTransport;
        if (wifiBlynkClient)
            delete wifiBlynkClient;
        blynk = nullptr;
        blynkTransport = nullptr;
        wifiBlynkClient = nullptr;
    }
    if (mqttClient) {
        mqttClient->disconnect();
        delete mqttClient;
        if (wifiMqttClient)
            delete wifiMqttClient;
        wifiMqttClient = nullptr;
        mqttClient = nullptr;
    }

    if (!websocket) {
        websocket = new WebSocketsServer(WEBSOCKET_PORT);
        websocket->begin();
        websocket->onEvent(webSocketEvent);
    }

    taskLoopConnection->enable();
}

void setupBlynk() {
    if (websocket) {
        websocket->close();
        delete websocket;
        websocket = nullptr;
    }

    if (!blynk) {
        wifiBlynkClient = new WiFiClient();
        blynkTransport = new BlynkArduinoClient(*wifiBlynkClient);
        blynk = new BlynkWifi(*blynkTransport);
        blynk->config(BLYNK_AUTH_TOKEN);
        taskConnectToBlynk->enable();
    }
}

void setupMqtt() {
    if (websocket) {
        websocket->close();
        delete websocket;
        websocket = nullptr;
    }
    if (blynk) {
        blynk->disconnect();
        delete blynk;
        if (blynkTransport)
            delete blynkTransport;
        if (wifiBlynkClient)
            delete wifiBlynkClient;
        blynk = nullptr;
        blynkTransport = nullptr;
        wifiBlynkClient = nullptr;
    }

    if (!mqttClient) {
        wifiMqttClient = new WiFiClientSecure();
        wifiMqttClient->setInsecure();
        mqttClient = new PubSubClient(*wifiMqttClient);
        mqttClient->setServer(TLS_MQTT_URL, TLS_MQTT_PORT);
        mqttClient->setCallback([](char* topic, uint8_t* payload, unsigned int length) {
            handleMessage(254, payload);
            });

    }
}


void setupConnection() {
    if (wifiConfig.mode == WEBSOCKET) {
        WiFi.mode(WIFI_AP);
        WiFi.softAP(wifiConfig.ssidAp.c_str(), wifiConfig.passwordAp.c_str());
        Serial.println("Access Point đã được kích hoạt!");
        Serial.print("SSID: "); Serial.println(wifiConfig.ssidAp);
        Serial.print("IPAP: "); Serial.println(WiFi.softAPIP());
        setupWebSocket();
    }
    else {
        WiFi.mode(WIFI_STA);
        WiFi.begin(wifiConfig.ssid.c_str(), wifiConfig.password.c_str());
        Serial.println("Kết nối tới WiFi...");
        taskConnectWiFi->enable();
    }
}


void sendMessage(uint8_t num, String& message) {
    if (websocket) {
        websocket->sendTXT(num, message);
    }
    else if (blynk) {
        blynk->virtualWrite(V3, message);
        blynk->virtualWrite(V2, 1);
    }
    else if (mqttClient) {
        mqttClient->publish(MQTT_TOPIC_COMMAND, message.c_str());
    }
}

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
        DateTime timestamp;
        const DateTime* tsPtr = nullptr;
        if (rtc.isrunning()) {
            timestamp = rtc.now();
            tsPtr = &timestamp;
        }
        sprayController.on(duration, SPRAY_REASON_MANUAL, tsPtr);
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
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
        if (responseDoc["status"] == true) {
            sprayScheduler.save();
        }
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
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
        if (responseDoc["status"] == true) {
            sprayScheduler.save();
        }
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
                responseDoc["status"] = false;
                responseDoc["message"] = "Task not found.";
            }
        }
        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
        if (responseDoc["status"] == true) {
            sprayScheduler.save();
        }
    }

    /**
     * Xử lý lệnh chỉnh sửa lịch phun sương
     * example:
     * received JSON:
     * {
     *   "command": "editTaskSpray",
     *   "taskId": 1,
     *   "hour": 15,
     *   "minute": 45,
     *   "weekday": 3,
     *   "duration": 7000,
     *   "enabled": true
     * }
     *
     * response JSON:
     * {
     *   "command": "editTaskSprayResponse",
     *   "status": 1,
     *   "message":"success"
     * }
     */
    else if (command == "editTaskSpray") {
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
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
        if (responseDoc["status"] == true) {
            sprayScheduler.save();
        }
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
        sendMessage(num, responseStr);
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
        sendMessage(num, jsonStr);
        Serial.println("Sent all spray tasks JSON.");
    }

    /**
     * Xử lý lệnh đặt thời gian RTC
     * example:
     * received JSON:
     * {
     *   "command": "setTime",
     *   "timestamp": 1633024800
     * }
     *
     * response JSON:
     * {
     *   "command": "setTimeResponse",
     *   "status": 1,
     *   "message":"RTC time updated. Current time: 1633024800"
     * }
     */
    else if (command == "setTime") {
        uint32_t timestamp = doc["timestamp"] | 0;
        rtc.adjust(DateTime(timestamp));
        Serial.printf("RTC time set to %u\n", timestamp);
        String responseStr = "{\"command\":\"setTimeResponse\",\"status\":true,\"message\":\"RTC time updated. Current time: "
            + String(rtc.now().unixtime()) + "\"}";
        sendMessage(num, responseStr);
    }

    /**
     * Xử lý lệnh lấy dữ liệu trang chủ
     * example:
     * received JSON:
     * {
     *   "command": "getHomeData"
     * }
     *
     * response JSON:
     * {
     *   "command": "getHomeDataResponse",
     *   "tempature": 29.3,
     *   "humidity": 75.5,
     *   "lastSprayHourTime": 14,
     *   "lastSprayMinuteTime": 30,
     *   "lastSprayDurationMs": 5000,
     *   "lastSprayReason": 1,
     *   "nextSprayHourTime": 15,
     *   "nextSprayMinuteTime": 45,
     *   "nextSprayDurationMs": 7000,
     *   "totalSprayCount": 10,
     *   "totalSprayDuration": 60000
     * }
     */
    else if (command == "getHomeData") {
        JsonDocument responseDoc;
        responseDoc["command"] = "getHomeDataResponse";
        responseDoc["temperature"] = 29.3; //TODO
        responseDoc["humidity"] = 75.5; //TODO
        responseDoc["lastSprayHourTime"] = sprayScheduler.getLastSprayInfo().timestamp.hour();
        responseDoc["lastSprayMinuteTime"] = sprayScheduler.getLastSprayInfo().timestamp.minute();
        responseDoc["lastSprayDurationMs"] = sprayScheduler.getLastSprayInfo().durationMs;
        responseDoc["lastSprayReason"] = static_cast<uint8_t>(sprayScheduler.getLastSprayInfo().reason);
        responseDoc["nextSprayHourTime"] = sprayScheduler.getNextSprayInfo().timestamp.hour();
        responseDoc["nextSprayMinuteTime"] = sprayScheduler.getNextSprayInfo().timestamp.minute();
        responseDoc["nextSprayDurationMs"] = sprayScheduler.getNextSprayInfo().durationMs; // == 0 => no next spray
        
        SprayController::SprayDataTotal sprayDataTotal = sprayController.getSprayDataTotal();
        
        responseDoc["totalSprayCount"] = sprayDataTotal.totalSpraysCount;
        responseDoc["totalSprayDuration"] = sprayDataTotal.totalSprayDuration;

        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
    }

    else {
        Serial.printf("Unknown command: %s\n", command.c_str());
        String responseStr = "{\"command\":\"unknownCommandResponse\",\"status\":false,\"message\":\"Unknown command.\"}";
        sendMessage(num, responseStr);
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

/*
    V0(int) trạng online/offline, 1 là online, 0 là offline
    V1(string) dữ liệu từ app -> esp
    V2(int) kiểm tra có dữ liệu từ esp gửi lên app, 1 là có dữ liệu, 0 là không có
    V3(string) dữ liệu từ esp gửi lên app
*/

BLYNK_WRITE(V0) {
    Serial.println("pong");
    blynk->virtualWrite(V0, 1);
}

BLYNK_WRITE(V1) {
    uint8_t* payload = (uint8_t*)param.asString();
    Serial.printf("Blynk Receive Text: %s\n", payload);
    handleMessage(255, payload);
}

