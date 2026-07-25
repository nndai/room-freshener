#include <Arduino.h>
#include "Config.h"
#include <Wire.h>
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
#include <PubSubClient.h>
#include <ESP8266HTTPClient.h>
#include "RequestWifiInet.h"
#include <NTPClient.h>
#include <WiFiUdp.h>
#include <DHT.h>

SystemConfig systemConfig = {
    WEBSOCKET,
    WIFIAP_SSID_DEFAULT,
    WIFIAP_PASSWORD_DEFAULT,
    "",
    "",
    1000,
};
ADC_MODE(ADC_VCC);

WebSocketsServer* websocket = nullptr;

WiFiClientSecure* wifiMqttClient = nullptr;
PubSubClient* mqttClient = nullptr;

WiFiUDP* ntpUDP = nullptr;
NTPClient* ntpClient = nullptr;

RTC_DS1307 rtc;
SprayController sprayController(SPRAY_PIN, &rtc, PATH_FOLDER_SPRAY_LOG, PATH_FILENAME_SPRAY_DATA_TOTAL);
SprayScheduler sprayScheduler(&sprayController, &rtc, PATH_FILENAME_SPRAY_TASKS);

LedController led(LED_PIN, true);
OneButton button(BUTTON_PIN, true);

DHT dht(DHT11_PIN, DHT11);
float latestTemperature = 0;
float latestHumidity = 0;

Scheduler mainScheduler;

//======================== Prototypes ========================
void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length);
void setupTask();
void setupWebSocket();
void setupMqtt();
void setupConnection();
void handleMessage(uint8_t num, uint8_t* payload, uint32_t length);
void printEspInfo();
String getEspInfo();
void printBanner();
void setupButton();
void handleButtonLongPress();

//======================== Tasks =============================
Task* taskUpdateSprayScheduler;
Task* taskSprayControllerUpdate;
Task* taskUpdateLed;
Task* taskButtonCheck;
Task* taskConnectToMqtt;
Task* taskConnectWiFi;
Task* taskLoopConnection;
Task* taskRequestWifiInet;
Task* taskReadDHT11;
Task* taskNtpSync;


//========================= Setup & Loop ======================
void setup() {
    led.on();
    Serial.begin(74880);
    delay(100);

    LittleFS.begin();
    // LittleFS.format(); // Uncomment this line to format LittleFS on first run

    printEspInfo();

    Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
    if (!rtc.begin(&Wire)) {
        Serial.println("Failed to initialize RTC.");
    }
    if (!rtc.isrunning()) {
        Serial.println("RTC is NOT running");
    }

    dht.begin();
    setupButton();
    setupTask();
    loadSystemConfig(systemConfig);

    sprayScheduler.load();
    sprayController.loadSprayDataTotal();
    setupConnection();

    led.blink(2000);
}

void loop() {
    mainScheduler.execute();
}

//=============================================================

void printBanner() {
    Serial.println("  _____");
    Serial.println(" |  __ \\");
    Serial.println(" | |__) |___   ___  _ __ ___");
    Serial.println(" |  _  // _ \\ / _ \\| '_ ` _ \\");
    Serial.println(" | | \\ \\ (_) | (_) | | | | | |");
    Serial.println(" |_|__\\_\\___/ \\___/|_| |_| |_|");
    Serial.println(" |  ____|           | |");
    Serial.println(" | |__ _ __ ___  ___| |__   ___ _ __   ___ _ __ ");
    Serial.println(" |  __| '__/ _ \\/ __| '_ \\ / _ \\ '_ \\ / _ \\ '__|");
    Serial.println(" | |  | | |  __/\\__ \\ | | |  __/ | | |  __/ |   ");
    Serial.print(" |_|  |_|  \\___||___/_| |_|\\___|_| |_|\\___|_|v");
    Serial.println(VERSION);
}

String getEspInfo() {
    String info;

    info += "ESP Information:\n";

    info += "Chip ID: " + String(ESP.getChipId()) + "\n";
    info += "Core Version: " + String(ESP.getCoreVersion()) + "\n";
    info += "SDK Version: " + String(ESP.getSdkVersion()) + "\n";

    info += "CPU Frequency: " + String(ESP.getCpuFreqMHz()) + " MHz\n";

    info += "Flash Chip ID: 0x" + String(ESP.getFlashChipId(), HEX) + "\n";
    info += "Flash Chip Size: " + String(ESP.getFlashChipSize() / 1024) + " KB\n";
    info += "Flash Real Size: " + String(ESP.getFlashChipRealSize() / 1024) + " KB\n";
    info += "Flash Chip Speed: " + String(ESP.getFlashChipSpeed() / 1000000) + " MHz\n";
    info += "Flash Mode: " + String(ESP.getFlashChipMode()) + "\n";
    info += "Flash Vendor ID: 0x" + String(ESP.getFlashChipVendorId(), HEX) + "\n";

    FSInfo fs_info;
    if (LittleFS.info(fs_info)) {
        info += "LittleFS Total Space: " + String(fs_info.totalBytes / 1024) + " KB\n";
        info += "LittleFS Used Space: " + String(fs_info.usedBytes / 1024) + " KB\n";
    }

    info += "Free Heap: " + String(ESP.getFreeHeap()) + " bytes\n";
    info += "Heap Fragmentation: " + String(ESP.getHeapFragmentation()) + "%\n";
    info += "Max Free Block: " + String(ESP.getMaxFreeBlockSize()) + " bytes\n";

    info += "Sketch Size: " + String(ESP.getSketchSize() / 1024) + " KB\n";
    info += "Free Sketch Space: " + String(ESP.getFreeSketchSpace() / 1024) + " KB\n";
    info += "Sketch MD5: " + String(ESP.getSketchMD5()) + "\n";

    info += "Reset Reason: " + String(ESP.getResetReason()) + "\n";
    info += "Boot Mode: " + String(ESP.getBootMode()) + "\n";

    info += "Vcc: " + String(ESP.getVcc()) + " mV\n";

    return info;
}

void printEspInfo() {
    Serial.println("\n-----------------------");
    printBanner();
    Serial.println();
    Serial.println(getEspInfo());
    Serial.println("-----------------------\n");
}


void setupTask() {
    Serial.println("Setting up tasks...");
    taskUpdateSprayScheduler = new Task(2000, TASK_FOREVER, []() {
        sprayScheduler.update();
        }, &mainScheduler, true);
    taskUpdateSprayScheduler->setSchedulingOption(TASK_INTERVAL);

    taskNtpSync = new Task(61000, TASK_FOREVER, []() {
        bool wifiOk = (isWifiInetConnected() && isInternetWifiInetConnected()) ||
                      (!isWifiInetConnected() && WiFi.status() == WL_CONNECTED && WiFi.getMode() != WIFI_AP);
        if (!wifiOk) return;

        if (!ntpClient) {
            ntpUDP = new WiFiUDP();
            ntpClient = new NTPClient(*ntpUDP);
            ntpClient->begin();
        }
        if (ntpClient->update()) {
            DateTime dt = DateTime(ntpClient->getEpochTime() + 1);
            rtc.adjust(dt);
        }
        }, &mainScheduler, true);
    taskNtpSync->setSchedulingOption(TASK_INTERVAL);

    taskSprayControllerUpdate = new Task(500, TASK_FOREVER, []() {
        sprayController.update();
        }, &mainScheduler, true);
    taskSprayControllerUpdate->setSchedulingOption(TASK_INTERVAL);

    taskUpdateLed = new Task(10, TASK_FOREVER, []() {
        led.update();
        }, &mainScheduler, true);
    taskUpdateLed->setSchedulingOption(TASK_INTERVAL);

    taskButtonCheck = new Task(10, TASK_FOREVER, []() {
        button.tick();
        }, &mainScheduler, true);
    taskButtonCheck->setSchedulingOption(TASK_INTERVAL);

    taskReadDHT11 = new Task(2000, TASK_FOREVER, []() {
        float h = dht.readHumidity();
        float t = dht.readTemperature();
        if (!isnan(h) && !isnan(t)) {
            latestHumidity = h;
            latestTemperature = t - 1.7f; // Adjust temperature reading by subtracting 2.2 degrees
        }
        }, &mainScheduler, true);
    taskReadDHT11->setSchedulingOption(TASK_INTERVAL);

    taskConnectToMqtt = new Task(2000, TASK_FOREVER, []() {
        led.blink(3, 300, 5000);
        if (isWifiInetConnected()) {
            if (isInternetWifiInetConnected()) {
                Serial.println("WiFi INET is connected. Connecting to MQTT Broker...");
            }
            else {
                Serial.println("WiFi INET is NOT Internet connected.");
                return;
            }
        }

        String clientID = "ESPClient-";
        clientID += String(random(0xffff), HEX);
        mqttClient->setSocketTimeout(2);
        if (mqttClient && mqttClient->connect(clientID.c_str(), TLS_MQTT_USERNAME, TLS_MQTT_PASSWORD)) {
            Serial.println("Connected to MQTT Broker!");
            mqttClient->subscribe(MQTT_TOPIC_RECEIVE);
            taskConnectToMqtt->disable();
            taskLoopConnection->enable();
        }
        mqttClient->setSocketTimeout(MQTT_SOCKET_TIMEOUT_OVERRIDE);
        }, &mainScheduler, false);
    taskConnectToMqtt->setSchedulingOption(TASK_INTERVAL);

    taskConnectWiFi = new Task(200, TASK_FOREVER, []() {
        led.blink(2, 300, 5000);
        if (WiFi.status() == WL_CONNECTED) {
            Serial.println("Connected to WiFi!");
            Serial.print("IP address: ");
            Serial.println(WiFi.localIP());
            Serial.print("Signal strength (RSSI): ");
            Serial.print(WiFi.RSSI());
            Serial.println(" dBm");

            if (isWifiInetConnected()) {
                Serial.println("Connected to WiFi INET. Starting login loop...");
                startWifiInetLogin();
                forceWifiInetLogin();
                taskRequestWifiInet->enable();
            }

            if (systemConfig.mode == MQTT) {
                setupMqtt();
            }
            taskConnectWiFi->disable();
        }
        }, &mainScheduler, false);
    taskConnectWiFi->setSchedulingOption(TASK_INTERVAL);

    taskLoopConnection = new Task(5, TASK_FOREVER, []() {
        if (websocket) {
            websocket->loop();
        }
        else if (mqttClient) {
            mqttClient->loop();
            if (!mqttClient->connected()) {
                Serial.println("MQTT Broker disconnected. Reconnecting...");
                taskConnectToMqtt->enable();
                taskLoopConnection->disable();
            }
            else {
                led.blink(1000);
            }
        }
        }, &mainScheduler, false);
    taskLoopConnection->setSchedulingOption(TASK_INTERVAL);

    taskRequestWifiInet = new Task(100, TASK_FOREVER, []() {
        loopWifiInetLogin();
        }, &mainScheduler, false);
    taskRequestWifiInet->setSchedulingOption(TASK_INTERVAL);
}

void setupButton() {
    button.setPressMs(10000);
    button.attachClick([]() {
        Serial.println("Button clicked!");
        if (sprayController.isActive()) {
            sprayController.off();
            Serial.println("Spray stopped by hardware button.");
            return;
        }

        sprayScheduler.sprayNow(systemConfig.hwButtonDurationMs, SPRAY_REASON_MANUAL_HARDWARE);
        });

    button.attachLongPressStart([]() {
        Serial.println("Button long pressed!");
        handleButtonLongPress();
        });
}

void handleButtonLongPress() {
    led.off();
    while (digitalRead(BUTTON_PIN) == LOW) {
        led.on();
        delay(200);
        led.off();
        delay(200);
    }

    systemConfig.mode = WEBSOCKET;
    setupConnection();
}

void setupWebSocket() {
    Serial.println("Setting up WebSocket server...");
    if (mqttClient) {
        Serial.println("Disconnecting from MQTT Broker...");
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
        Serial.println("WebSocket server started.");
    }
    taskLoopConnection->enable();
}

void setupMqtt() {
    Serial.println("Setting up MQTT connection...");
    if (websocket) {
        websocket->close();
        delete websocket;
        websocket = nullptr;
    }

    if (!mqttClient) {
        wifiMqttClient = new WiFiClientSecure();
        wifiMqttClient->setInsecure();
        wifiMqttClient->setBufferSizes(MQTT_MAX_PACKET_SIZE_OVERRIDE, MQTT_MAX_PACKET_SIZE_OVERRIDE);
        mqttClient = new PubSubClient(*wifiMqttClient);
        mqttClient->setBufferSize(MQTT_MAX_PACKET_SIZE_OVERRIDE);
        mqttClient->setSocketTimeout(MQTT_SOCKET_TIMEOUT_OVERRIDE);
        mqttClient->setServer(TLS_MQTT_URL, TLS_MQTT_PORT);
        mqttClient->setCallback([](char* topic, uint8_t* payload, unsigned int length) {
            handleMessage(254, payload, length);
            });
        Serial.println("Connecting to MQTT Broker...");
        taskConnectToMqtt->enable();
    }
}


void setupConnection() {
    Serial.println("Setting up connection...");
    if (isWifiInetRunning()) {
        stopWifiInetLogin();
        taskRequestWifiInet->disable();
    }

    if (systemConfig.mode == WEBSOCKET) {
        WiFi.mode(WIFI_AP);
        WiFi.softAP(systemConfig.ssidAp.c_str(), systemConfig.passwordAp.c_str());
        Serial.println("Access Point has been activated!");
        Serial.print("SSID: "); Serial.println(systemConfig.ssidAp);
        Serial.print("IPAP: "); Serial.println(WiFi.softAPIP());
        setupWebSocket();
    }
    else {
        WiFi.mode(WIFI_STA);
        if (systemConfig.password.length() == 0) {
            WiFi.begin(systemConfig.ssid.c_str());
        }
        else {
            WiFi.begin(systemConfig.ssid.c_str(), systemConfig.password.c_str());
        }
        Serial.println("Connecting to WiFi...");
        taskConnectWiFi->enable();
    }
}


void sendMessage(uint8_t num, String& message) {
    if (websocket) {
        websocket->sendTXT(num, message);
    }
    else if (mqttClient) {
        if (!mqttClient->publish(MQTT_TOPIC_SEND, message.c_str())) {
            Serial.println("Failed to send MQTT message.");
        }
    }
}

void handleMessage(uint8_t num, uint8_t* payload, uint32_t length) {
    payload[length] = '\0'; // Ensure null-terminated string
    Serial.printf("[%u] Received Text: %s\n", num, payload);

    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, payload);
    if (error) {
        Serial.print("deserializeJson() failed: ");
        Serial.println(error.f_str());
        return;
    }

    // List of supported commands:
    /*
        sprayNow
        stopSpray
        addTaskSpray
        removeTaskSpray
        setTaskEnabled
        editTaskSpray
        getTime
        getAllTaskSpray
        setTime
        getHomeData
        setWiFiConfig
        getEspInfo
        getLogFiles
        readLogFile
    */

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
     * response JSON:
     * {
     *  "command": "sprayNowResponse",
     *  "status": 1,
     *  "message": "success"
     * }
     */
    if (command == "sprayNow") {
        uint32_t duration = doc["duration"] | 1000;
        sprayScheduler.sprayNow(duration, SPRAY_REASON_MANUAL_APP);
        Serial.printf("Spraying for %u ms\n", duration);
        JsonDocument responseDoc;
        responseDoc["command"] = "sprayNowResponse";
        responseDoc["status"] = true;
        responseDoc["message"] = "success.";
        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
    }

    /**
     * Xử lý lệnh tắt phun sương ngay lập tức
     * example:
     * received JSON:
     * {
     *   "command": "stopSpray"
     * }
     *
     * response JSON:
     * {
     *  "command": "stopSprayResponse",
     *  "status": 1,
     *  "wasActive": true,
     *  "message": "success."
     * }
     */
    else if (command == "stopSpray") {
        bool wasActive = sprayController.isActive();
        if (wasActive) {
            sprayController.off();
        }

        JsonDocument responseDoc;
        responseDoc["command"] = "stopSprayResponse";
        responseDoc["status"] = true;
        responseDoc["wasActive"] = wasActive;
        responseDoc["message"] = wasActive ? "success." : "Spray already stopped.";

        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
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
        if (timestamp == 0) {
            Serial.println("Invalid timestamp.");
            String responseStr = "{\"command\":\"setTimeResponse\",\"status\":false,\"message\":\"Invalid timestamp.\"}";
            sendMessage(num, responseStr);
            return;
        }
        rtc.adjust(DateTime(timestamp + 1));
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
        responseDoc["temperature"] = latestTemperature;
        responseDoc["humidity"] = latestHumidity;

        SprayInfo lastSprayInfo = sprayScheduler.getLastSprayInfo();
        responseDoc["lastSprayHourTime"] = lastSprayInfo.timestamp.hour();
        responseDoc["lastSprayMinuteTime"] = lastSprayInfo.timestamp.minute();
        responseDoc["lastSprayDurationMs"] = lastSprayInfo.durationMs;
        responseDoc["lastSprayReason"] = static_cast<uint8_t>(lastSprayInfo.reason);

        SprayInfo nextSprayInfo = sprayScheduler.getNextSprayInfo();
        responseDoc["nextSprayHourTime"] = nextSprayInfo.timestamp.hour();
        responseDoc["nextSprayMinuteTime"] = nextSprayInfo.timestamp.minute();
        responseDoc["nextSprayDurationMs"] = nextSprayInfo.durationMs; // == 0 => no next spray

        SprayController::SprayDataTotal sprayDataTotal = sprayController.getSprayDataTotal();
        responseDoc["totalSprayCount"] = sprayDataTotal.totalSpraysCount;
        responseDoc["totalSprayDuration"] = sprayDataTotal.totalSprayDuration;
        responseDoc["sprayActive"] = sprayController.isActive();

        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
    }

    /**
     * Xử lý lệnh đặt cấu hình WiFi
     * example:
     * received JSON:
     * {
     *   "command": "setWiFiConfig",
     *   "ssidAp": "MyESPAP",
     *   "passwordAp": "password123",
     *   "ssid": "MyWiFi",
     *   "password": "wifiPassword",
     *   "modeConnect": 1
     * }
     *
     * response JSON:
     * {
     *   "command": "setWiFiConfigResponse",
     *   "status": 1,
     *   "message":"WiFi configuration updated. Rebooting..."
     * }
     */
    else if (command == "setWiFiConfig") {
        String ssidAp = doc["ssidAp"] | "";
        String passwordAp = doc["passwordAp"] | "";
        String ssid = doc["ssid"] | "";
        String password = doc["password"] | "";
        uint8_t mode = doc["modeConnect"] | 0;

        if (ssidAp.length() > 32 || passwordAp.length() > 64 ||
            ssid.length() > 32 || password.length() > 64 ||
            mode > MQTT) {
            Serial.println("Invalid WiFi configuration parameters.");
            String responseStr = "{\"command\":\"setWiFiConfigResponse\",\"status\":false,\"message\":\"Invalid parameters.\"}";
            sendMessage(num, responseStr);
            return;
        }

        // If SSID is empty, keep the old value
        if (ssidAp.length() < 1) {
            ssidAp = systemConfig.ssidAp;
            passwordAp = systemConfig.passwordAp;
        }

        if (ssid.length() < 1) {
            ssid = systemConfig.ssid;
            password = systemConfig.password;
        }

        if (systemConfig.mode == mode && systemConfig.ssidAp == ssidAp && systemConfig.passwordAp == passwordAp &&
            systemConfig.ssid == ssid && systemConfig.password == password) {
            Serial.println("WiFi configuration is the same as the current one. No changes made.");
            String responseStr = "{\"command\":\"setWiFiConfigResponse\",\"status\":true,\"message\":\"WiFi configuration is the same as the current one. No changes needed.\"}";
            sendMessage(num, responseStr);
            return;
        }

        systemConfig.ssidAp = ssidAp;
        systemConfig.passwordAp = passwordAp;
        systemConfig.ssid = ssid;
        systemConfig.password = password;
        systemConfig.mode = static_cast<ModeConnect>(mode);

        saveSystemConfig(systemConfig);

        String responseStr = "{\"command\":\"setWiFiConfigResponse\",\"status\":true,\"message\":\"WiFi configuration updated. Rebooting...\"}";
        sendMessage(num, responseStr);

        Serial.println("WiFi config updated. Rebooting...");
        delay(1000);
        ESP.restart();
    }

    /**
     * Xử lý lệnh lấy thông tin ESP
     * example:
     * received JSON:
     * {
     *   "command": "getEspInfo"
     * }
     *
     * response JSON:
     * {
     *   "command": "getEspInfoResponse",
     *   "chipId": 1234567,
     *   "coreVersion": "2_7_4",
     *   ...
     * }
     */
    else if (command == "getEspInfo") {
        JsonDocument responseDoc;
        responseDoc["command"] = "getEspInfoResponse";

        // Runtime
        responseDoc["loopMqttRunning"] = taskLoopConnection->isEnabled() && mqttClient != nullptr;
        responseDoc["loopWebsocketRunning"] = taskLoopConnection->isEnabled() && websocket != nullptr;

        // ESP Info
        responseDoc["chipId"] = ESP.getChipId();
        responseDoc["coreVersion"] = ESP.getCoreVersion();
        responseDoc["sdkVersion"] = ESP.getSdkVersion();
        responseDoc["cpuFreqMHz"] = ESP.getCpuFreqMHz();

        // Flash
        responseDoc["flashChipId"] = String(ESP.getFlashChipId(), HEX);
        responseDoc["flashChipSizeKb"] = ESP.getFlashChipSize() / 1024;
        responseDoc["flashChipRealSizeKb"] = ESP.getFlashChipRealSize() / 1024;
        responseDoc["flashChipSpeedMHz"] = ESP.getFlashChipSpeed() / 1000000;
        responseDoc["flashChipMode"] = ESP.getFlashChipMode();

        // File System
        FSInfo fs_info;
        if (LittleFS.info(fs_info)) {
            responseDoc["fsTotalBytes"] = fs_info.totalBytes;
            responseDoc["fsUsedBytes"] = fs_info.usedBytes;
        }
        else {
            responseDoc["fsTotalBytes"] = 0;
            responseDoc["fsUsedBytes"] = 0;
        }

        // Memory
        responseDoc["freeHeap"] = ESP.getFreeHeap();
        responseDoc["heapFragmentation"] = ESP.getHeapFragmentation();
        responseDoc["maxFreeBlockSize"] = ESP.getMaxFreeBlockSize();

        // Sketch
        responseDoc["sketchSizeKb"] = ESP.getSketchSize() / 1024;
        responseDoc["freeSketchSpaceKb"] = ESP.getFreeSketchSpace() / 1024;
        responseDoc["sketchMD5"] = ESP.getSketchMD5();

        // System
        responseDoc["resetReason"] = ESP.getResetReason();
        responseDoc["bootMode"] = ESP.getBootMode();
        responseDoc["vccMv"] = ESP.getVcc();
        responseDoc["appVersion"] = VERSION;

        // System Config
        responseDoc["hwButtonDurationMs"] = systemConfig.hwButtonDurationMs;
        responseDoc["configMode"] = systemConfig.mode;
        responseDoc["configSsidAp"] = systemConfig.ssidAp;
        responseDoc["configSsid"] = systemConfig.ssid;

        // WiFi
        responseDoc["wifiSsid"] = WiFi.SSID();
        responseDoc["wifiRssi"] = WiFi.RSSI();
        responseDoc["wifiMode"] = (int)WiFi.getMode();
        responseDoc["wifiStatus"] = WiFi.status();
        if (WiFi.isConnected()) {
            responseDoc["wifiIp"] = WiFi.localIP().toString();
            responseDoc["wifiGateway"] = WiFi.gatewayIP().toString();
            responseDoc["wifiSubnet"] = WiFi.subnetMask().toString();
            responseDoc["wifiMac"] = WiFi.macAddress();
            responseDoc["wifiChannel"] = WiFi.channel();
        }

        responseDoc["wifiAutoReconnect"] = WiFi.getAutoConnect();
        responseDoc["wifiSleepMode"] = WiFi.getSleepMode();

        uint32_t uptimeMs = millis();
        uint32_t seconds = uptimeMs / 1000;
        uint32_t minutes = seconds / 60;
        uint32_t hours = minutes / 60;
        uint32_t days = hours / 24;
        seconds %= 60;
        minutes %= 60;
        hours %= 24;

        char uptimeStr[32];
        snprintf(uptimeStr, sizeof(uptimeStr), "%02lud %02luh %02lum %02lus", (unsigned long)days, (unsigned long)hours, (unsigned long)minutes, (unsigned long)seconds);
        responseDoc["uptime"] = uptimeStr;

        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
    }

    /**
     * Xử lý lệnh lấy danh sách file log
     * example:
     * received JSON:
     * {
     *   "command": "getLogFiles"
     * }
     */
    else if (command == "getLogFiles") {
        std::vector<SprayController::LogFileInfo> files = sprayController.collectLogFiles();

        JsonDocument responseDoc;
        responseDoc["command"] = "getLogFilesResponse";
        JsonArray filesArray = responseDoc["files"].to<JsonArray>();

        for (const auto& info : files) {
            JsonObject fileObj = filesArray.add<JsonObject>();
            fileObj["name"] = info.path;
            fileObj["size"] = info.size;
        }

        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
    }

    /**
     * Xử lý lệnh đọc từng phần của file log
     * example:
     * received JSON:
     * {
     *   "command": "readLogFile",
     *   "name": "/logs/spray/log-3-2026.log",
     *   "offset": 0
     * }
     */
    else if (command == "readLogFile") {
        String name = doc["name"] | "";
        uint32_t offset = doc["offset"] | 0;

        if (name == "") {
            String responseStr = "{\"command\":\"readLogFileResponse\",\"status\":false,\"message\":\"Missing file name.\"}";
            sendMessage(num, responseStr);
            return;
        }

        bool isEOF = false;
        String data = sprayController.readLogChunk(name, offset, 512, isEOF);

        JsonDocument responseDoc;
        responseDoc["command"] = "readLogFileResponse";
        responseDoc["name"] = name;
        responseDoc["offset"] = offset + data.length();
        responseDoc["data"] = data;
        responseDoc["isEOF"] = isEOF;

        String jsonStr;
        serializeJson(responseDoc, jsonStr);
        sendMessage(num, jsonStr);
    }
    else if (command == "setSystemSettings") {
        uint32_t duration = doc["hwButtonDurationMs"] | 1000;
        systemConfig.hwButtonDurationMs = duration;
        saveSystemConfig(systemConfig);

        Serial.printf("System settings updated: HW button duration = %u ms\n", duration);

        JsonDocument responseDoc;
        responseDoc["command"] = "setSystemSettingsResponse";
        responseDoc["status"] = true;
        responseDoc["message"] = "Cấu hình hệ thống đã được lưu";

        String responseStr;
        serializeJson(responseDoc, responseStr);
        sendMessage(num, responseStr);
        return;
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
        handleMessage(num, payload, length);
        break;

    case WStype_BIN:
        Serial.printf("[%u] Received binary, length: %u\n", num, length);
        break;

    default:
        break;
    }
}

