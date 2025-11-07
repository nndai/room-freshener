#ifndef SPRAYSCHEDULER_H
#define SPRAYSCHEDULER_H

#include <Arduino.h>
#include <vector>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <RTClib.h>
#include <Base_64.h>
#include "SprayController.h"


/**
 * Cấu trúc một lịch phun (task).
 */
struct SprayTask {
    uint8_t id;         // ID duy nhất
    uint8_t hour;       // giờ
    uint8_t minute;     // phút
    uint8_t weekday;    // 0=Sun ... 6=Sat, 7=Everyday
    bool enabled;       // có bật hay không
    uint16_t duration;  // thời gian phun (ms)  
};

class SprayScheduler {
private:
    std::vector<SprayTask> tasks;
    SprayController* spray;

    RTC_DS1307* rtc;
    std::vector<bool> triggered;

    uint8_t lastMinute = 255;

public:
    SprayScheduler(SprayController* sc, RTC_DS1307* rtcModule)
        : spray(sc), rtc(rtcModule), triggered(256, false) {
    }

    /**
     * Thêm hoặc chỉnh sửa một lịch phun.
     * @param id ID của lịch phun (0 để thêm mới).
     * @param hour Giờ phun.
     * @param minute Phút phun.
     * @param weekday Ngày trong tuần (0=Chủ nhật ... 6=Thứ Bảy, 7=Mỗi ngày).
     * @param duration Thời gian phun (ms).
     * @param enabled Có bật lịch phun hay không.
     * @return ID của lịch phun mới hoặc đã chỉnh sửa, hoặc 0 nếu thất bại.
     */
    uint8_t addTask(uint8_t id, uint8_t hour, uint8_t minute, uint8_t weekday, uint16_t duration, bool enabled = true) {
        if (id == 0) {
            id = getNextTaskId();
            if(id == 255) {
                return 0; // full
            }

            SprayTask task = { id, hour, minute, weekday, enabled, duration };
            tasks.push_back(task);
            triggered[id] = false;
            return task.id;
        }
        else {
            if (editTask({ id, hour, minute, weekday, enabled, duration })){
                return id;
            }

            return 0; // not found 
        }
    }

    /**
     * Xóa một lịch phun theo ID.
     * @param id ID của lịch phun cần xóa.
     * @return true nếu xóa thành công, false nếu không tìm thấy.
     */
    bool removeTask(uint32_t id) {
        for (size_t i = 0; i < tasks.size(); ++i) {
            if (tasks[i].id == id) {
                tasks.erase(tasks.begin() + i);
                triggered[id] = false;
                return true;
            }
        }
        return false;
    }

    /**
     * Chỉnh sửa một lịch phun.
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
     * Lấy ID tiếp theo chưa sử dụng.
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
     * Lưu danh sách lịch phun vào file.
     * @param filename Tên file để lưu.
     * @return true nếu lưu thành công, false nếu thất bại.
     */
    bool save(const char* filename) {
        File file = LittleFS.open(filename, "w");
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
     * Tải danh sách lịch phun từ file.
     * @param filename Tên file để tải.
     * @return true nếu tải thành công, false nếu thất bại.
     */
    bool load(const char* filename) {
        File file = LittleFS.open(filename, "r");
        if (!file) return false;

        uint32_t count = 0;
        file.read((uint8_t*)&count, sizeof(count));

        tasks.clear();
        triggered.clear();

        for (uint32_t i = 0; i < count; i++) {
            SprayTask t;
            if (file.read((uint8_t*)&t, sizeof(SprayTask)) == sizeof(SprayTask)) {
                tasks.push_back(t);
                triggered.push_back(false);
            }
            else {
                break; // file bị hỏng
            }
        }

        file.close();
        return true;
    }

    /**
     * Cập nhật trạng thái lịch phun.
     * Phải được gọi liên tục trong vòng lặp chính.
     */
    void update() {
        if (!rtc->isrunning()) return;
        DateTime now = rtc->now();

        if (now.minute() != lastMinute) {
            std::fill(triggered.begin(), triggered.end(), false);
            lastMinute = now.minute();
        }

        for (size_t i = 0; i < tasks.size(); i++) {
            SprayTask& t = tasks[i];
            if (!t.enabled) continue;

            bool dayMatch = (t.weekday == 7 || t.weekday == now.dayOfTheWeek());
            bool timeMatch = (t.hour == now.hour() && t.minute == now.minute());

            if (timeMatch && dayMatch && !triggered[t.id]) {
                spray->on(t.duration);
                triggered[t.id] = true;
            }
        }
    }

    /**
     * Lấy con trỏ đến lịch phun theo ID.
     * @param id ID của lịch phun.
     * @return Con trỏ đến lịch phun, hoặc nullptr nếu không tìm thấy.
     */
    SprayTask* getTaskById(uint32_t id) {
        for (auto& t : tasks)
            if (t.id == id) return &t;
        return nullptr;
    }

    /**
     * Tạo chuỗi JSON từ danh sách lịch phun.
     * @return Chuỗi JSON đại diện cho danh sách lịch phun.
     */
    String createTasksJson() {
        JsonDocument doc;

        doc["countTask"] = tasks.size();
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

        String jsonStr;
        serializeJson(doc, jsonStr);
        return jsonStr;
    }

    /**
     * Phân tích chuỗi JSON để lấy danh sách lịch phun.
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

};

#endif
