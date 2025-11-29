#ifndef SPRAYSCHEDULER_H
#define SPRAYSCHEDULER_H

#include <Arduino.h>
#include <vector>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <RTClib.h>
#include <Base_64.h>
#include "SprayController.h"
#include "Config.h"


/**
 * @brief Cấu trúc một lịch phun (task).
 */
struct SprayTask {
    uint8_t id;         // ID duy nhất
    uint8_t hour;       // giờ
    uint8_t minute;     // phút
    uint8_t weekday;    // bit 0-6: CN-T7, weekday > 127 => lỗi
    bool enabled;       // có bật hay không
    uint32_t duration;  // thời gian phun (ms)  
};

struct SprayInfo {
    DateTime timestamp;
    uint32_t durationMs;
    SprayReason reason;
};

class SprayScheduler {
private:
    std::vector<SprayTask> tasks;
    SprayController* _spray;

    RTC_DS1307* _rtc;
    std::vector<bool> _triggered;

    uint8_t _lastMinute = 255;
    String _fileNameSave = "";
    SprayInfo _lastSprayInfo;

    void initTriggeredVector() {
        _triggered.assign(256, false);
    }

    bool wasTriggered(uint8_t id) const {
        return id < _triggered.size() ? _triggered[id] : false;
    }

    void setTriggered(uint8_t id, bool value) {
        if (id < _triggered.size()) {
            _triggered[id] = value;
        }
    }

public:
    /**
     * @brief Khởi tạo SprayScheduler.
     * @param sc Con trỏ đến đối tượng SprayController.
     * @param rtcModule Con trỏ đến đối tượng RTC_DS1307.
     * @param fileNameSave Tên file để lưu lịch phun.
     */
    SprayScheduler(SprayController* sc, RTC_DS1307* rtcModule, String fileNameSave = "/sprayTasks.bin")
        : _spray(sc), _rtc(rtcModule), _triggered(256, false), _fileNameSave(fileNameSave) {
        initTriggeredVector();
    }

    /**
     * @brief Kiểm tra tính hợp lệ của một lịch phun.
     * @param hour Giờ phun.
     * @param minute Phút phun.
     * @param weekday Ngày trong tuần (theo bitmask).
     * @param duration Thời gian phun (ms).
     * @return true nếu hợp lệ, false nếu không.
     */
    bool checkValidTask(uint8_t hour, uint8_t minute, uint8_t weekday, uint32_t duration) {
        if (hour > 23 || minute > 59 || weekday > 127 || duration == 0) {
            return false;
        }
        return true;
    }

    /**
     * @brief Thêm hoặc chỉnh sửa một lịch phun.
     * @param id ID của lịch phun (0 để thêm mới).
     * @param hour Giờ phun.
     * @param minute Phút phun.
     * @param weekday Ngày trong tuần (theo bitmask).
     * @param duration Thời gian phun (ms).
     * @param enabled Có bật lịch phun hay không.
     * @return ID của lịch phun mới hoặc đã chỉnh sửa, hoặc 0 nếu thất bại.
     */
    uint8_t addTask(uint8_t id, uint8_t hour, uint8_t minute, uint8_t weekday, uint32_t duration, bool enabled = true) {
        if (!checkValidTask(hour, minute, weekday, duration)) {
            return 0;
        }
        if (id == 0) {
            id = getNextTaskId();
            if (id == 255) {
                return 255; // full
            }

            SprayTask task = { id, hour, minute, weekday, enabled, duration };
            tasks.push_back(task);
            setTriggered(id, false);
            return task.id;
        }
        else {
            if (editTask({ id, hour, minute, weekday, enabled, duration })) {
                return id;
            }

            return 0; // not found 
        }
    }

    /**
     * @brief Xóa một lịch phun theo ID.
     * @param id ID của lịch phun cần xóa.
     * @return true nếu xóa thành công, false nếu không tìm thấy.
     */
    bool removeTask(uint8_t id) {
        for (size_t i = 0; i < tasks.size(); ++i) {
            if (tasks[i].id == id) {
                tasks.erase(tasks.begin() + i);
                setTriggered(id, false);
                return true;
            }
        }
        return false;
    }

    /**
     * @brief Chỉnh sửa một lịch phun.
     * @param newTask Cấu trúc lịch phun mới.
     * @return true nếu chỉnh sửa thành công, false nếu không tìm thấy.
     */
    bool editTask(const SprayTask& newTask) {
        for (auto& t : tasks) {
            if (t.id == newTask.id) {
                t = newTask;
                return true;
            }
        }
        return false;
    }

    /**
     * @brief Đặt trạng thái kích hoạt của một lịch phun.
     * @param id ID của lịch phun.
     * @param enabled Trạng thái kích hoạt mới.
     * @return true nếu thành công, false nếu không tìm thấy lịch phun.
     */
    bool setTaskEnabled(uint8_t id, bool enabled) {
        SprayTask* task = getTaskById(id);
        if (task) {
            task->enabled = enabled;
            return true;
        }
        return false;
    }

    /**
     * @brief Lấy ID tiếp theo chưa sử dụng.
     * @return ID tiếp theo (1-254), hoặc 255 nếu đầy.
     */
    uint8_t getNextTaskId() const {
        for (uint8_t id = 1; id < 255; ++id) {
            bool exists = false;
            for (const auto& t : tasks) {
                if (t.id == id) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                return id;
            }
        }
        return 255;
    }

    /**
     * @brief Lưu danh sách lịch phun vào file.
     * @param filename Tên file để lưu.
     * @return true nếu lưu thành công, false nếu thất bại.
     */
    bool save() {
        File file = LittleFS.open(_fileNameSave.c_str(), "w");
        if (!file) return false;

        uint32_t count = tasks.size();
        file.write((uint8_t*)&count, sizeof(count));

        for (auto& t : tasks) {
            file.write((uint8_t*)&t, sizeof(SprayTask));
        }

        file.close();
        return true;
    }

    /**
     * @brief Tải danh sách lịch phun từ file.
     * @param filename Tên file để tải.
     * @return true nếu tải thành công, false nếu thất bại.
     */
    bool load() {
        Serial.print("Loading spray schedule from file...");
        File file = LittleFS.open(_fileNameSave.c_str(), "r");
        if (!file) {
            Serial.println(" ---> Spray schedule file not found. Starting with an empty schedule.");
            return false;
        }

        uint32_t count = 0;
        file.read((uint8_t*)&count, sizeof(count));

        tasks.clear();
        initTriggeredVector();

        for (uint32_t i = 0; i < count; i++) {
            SprayTask t;
            if (file.read((uint8_t*)&t, sizeof(SprayTask)) == sizeof(SprayTask)) {
                tasks.push_back(t);
            }
            else {
                Serial.println(" ---> Error reading spray schedule from file. File may be corrupted.");
                break; // file bị hỏng
            }
        }

        file.close();
        Serial.println(" ---> success.");
        return true;
    }

    /**
     * @brief Cập nhật trạng thái lịch phun.
     * @note Phải được gọi liên tục trong vòng lặp chính.
     */
    void update() {
        if (!_rtc->isrunning()) {
            Serial.println("RTC is not running. Skipping update.");
            return;
        }
        DateTime now = CONVERT_TO_LOCAL_TIME(_rtc->now());
        Serial.printf("SprayScheduler: Current time %02d:%02d (minute %d)\n", now.hour(), now.minute(), now.minute());

        if (now.minute() != _lastMinute) {
            std::fill(_triggered.begin(), _triggered.end(), false);
            _lastMinute = now.minute();
        }

        for (size_t i = 0; i < tasks.size(); i++) {
            SprayTask& t = tasks[i];
            if (!t.enabled) continue;

            bool isOnceDaily = (t.weekday == 0);
            bool dayMatch = isOnceDaily || (t.weekday & (1 << now.dayOfTheWeek()));
            bool timeMatch = (t.hour == now.hour() && t.minute == now.minute());

            if (timeMatch && dayMatch && !wasTriggered(t.id)) {
                Serial.printf("SprayScheduler: Triggered spray task ID=%d at %02d:%02d on weekday mask 0x%02X for %u ms\n",
                    t.id, t.hour, t.minute, t.weekday, t.duration);

                _spray->on(t.duration, SPRAY_REASON_SCHEDULED, &now);
                _lastSprayInfo = { now, t.duration, SPRAY_REASON_SCHEDULED };
                setTriggered(t.id, true);
                if (isOnceDaily) {
                    t.enabled = false;
                    save();
                }
            }
        }
    }

    /**
     * @brief Lấy con trỏ đến lịch phun theo ID.
     * @param id ID của lịch phun.
     * @return Con trỏ đến lịch phun, hoặc nullptr nếu không tìm thấy.
     */
    SprayTask* getTaskById(uint8_t id) {
        for (auto& t : tasks)
            if (t.id == id) return &t;
        return nullptr;
    }

    /**
     * @brief Tạo chuỗi JSON từ danh sách lịch phun.
     * @return JSON đại diện cho danh sách lịch phun.
     * example JSON:
     * {
     *   "countask": 2,
     *   "sizeTask": 12,
     *   "dataTask": "***BASE64_ENCODED_DATA_HERE***"
     * }
     */
    JsonDocument createTasksJson() {
        JsonDocument doc;
        doc["countask"] = tasks.size();
        doc["sizeTask"] = sizeof(SprayTask);

        if (!tasks.empty()) {

            const char* rawPtr = reinterpret_cast<const char*>(tasks.data());
            size_t rawSize = tasks.size() * sizeof(SprayTask);

            int encodedLength = Base64.encodedLength(rawSize);
            char* encodedString = new char[encodedLength + 1];
            Base64.encode(encodedString, rawPtr, rawSize);

            doc["dataTask"] = String(encodedString);
            delete[] encodedString;
        }
        else {
            doc["dataTask"] = "";
        }

        return doc;
    }

    /**
     * @brief Phân tích chuỗi JSON để lấy danh sách lịch phun.
     * @param jsonStr Chuỗi JSON chứa danh sách lịch phun.
     * @param outTasks Tham chiếu đến vector để lưu danh sách lịch phun.
     * @return true nếu phân tích thành công, false nếu thất bại.
     */
    static bool parseTasksJson(const String& jsonStr, std::vector<SprayTask>& outTasks) {
        JsonDocument doc;
        DeserializationError error = deserializeJson(doc, jsonStr);
        if (error) {
            return false;
        }

        uint32_t countTask = doc["countTask"] | 0;
        size_t sizeTask = doc["sizeTask"] | 0;
        String dataTask = doc["dataTask"] | "";

        if (sizeTask != sizeof(SprayTask)) {
            return false;
        }

        if (dataTask.length() > 0) {

            int decodedLength = Base64.decodedLength(dataTask.c_str(), dataTask.length());
            if (decodedLength > 0)
            {
                char* decodedString = new char[decodedLength + 1];
                Base64.decode(decodedString, dataTask.c_str(), dataTask.length());

                outTasks.clear();
                for (size_t i = 0; i < countTask; i++) {
                    SprayTask t;
                    memcpy(&t, decodedString + i * sizeof(SprayTask), sizeof(SprayTask));
                    outTasks.push_back(t);
                }
                delete[] decodedString;
            }
            else {
                return false;
            }
        }
        else {
            outTasks.clear();
        }

        return true;
    }

    /**
     * @brief Kích hoạt phun sương ngay lập tức.
     * @param duration Thời gian phun (ms).
     * @param reason Lý do phun sương.
     * @param timestamp Thời gian phun (nếu có).
     */
    void sprayNow(uint32_t duration, SprayReason reason = SPRAY_REASON_OTHER, const DateTime* timestamp = nullptr) {
        _spray->on(duration, reason, timestamp);
        _lastSprayInfo = { timestamp ? *timestamp : (_rtc && _rtc->isrunning() ? _rtc->now() : DateTime()), duration, reason };
    }

    /**
     * @brief Lấy thông tin lần phun sương cuối cùng.
     * @return Thông tin lần phun sương cuối cùng.
     */
    SprayInfo getLastSprayInfo() const {
        return _lastSprayInfo;
    }

    SprayInfo getNextSprayInfo() const {
        DateTime now = _rtc->isrunning() ? _rtc->now() : DateTime();
        for (const auto& t : tasks) {
            if (!t.enabled) continue;

            bool isOnceDaily = (t.weekday == 0);
            bool dayMatch = isOnceDaily || (t.weekday & (1 << now.dayOfTheWeek()));
            DateTime scheduledTime(now.year(), now.month(), now.day(), t.hour, t.minute, 0);

            if (scheduledTime > now && dayMatch) {
                return { scheduledTime, t.duration, SPRAY_REASON_SCHEDULED };
            }
        }
        return { DateTime(), 0, SPRAY_REASON_OTHER };
    }

};

#endif
