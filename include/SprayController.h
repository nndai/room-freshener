#ifndef SPRAYCONTROLLER_H
#define SPRAYCONTROLLER_H

#include <Arduino.h>
#include <LittleFS.h>
#include <RTClib.h>
#include <vector>
#include <algorithm>



enum SprayReason {
    SPRAY_REASON_SCHEDULED,
    SPRAY_REASON_MANUAL_HARDWARE,
    SPRAY_REASON_MANUAL_APP,
    SPRAY_REASON_OTHER,
};



/**
 * Class điều khiển máy phun sương.
 */
class SprayController {
public:
    struct SprayDataTotal {
        uint32_t totalSpraysCount;
        uint32_t totalSprayDuration; // in seconds
    };

private:
    static constexpr uint8_t MAX_LOG_FILES = 6;
    uint8_t _pin;
    bool _active = false;
    uint32_t _durationMs = 0;
    uint32_t _startTime = 0;
    RTC_DS1307* _rtc = nullptr;
    String _pathFolderLog = "";
    String _pathFileNameSprayTotal = "";

    // LogFileInfo moved to public

    SprayDataTotal _sprayDataTotal;

    void logSprayEvent(uint32_t durationMs, SprayReason reason, const DateTime* timestamp);
    bool resolveTimestamp(const DateTime* timestamp, DateTime& outTimestamp) const;
    String normalizeFolderPath(const String& folder) const;
    String logDirWithSlash() const;
    String logDirWithoutSlash() const;
    void ensureLogDirectoryExists();
    void enforceLogRetention();
    // collectLogFiles moved to public
    bool parseLogFileDate(const String& fileName, uint16_t& year, uint8_t& month) const;
    String buildLogFilePath(const DateTime& timestamp) const;
    String formatTimestamp(const DateTime& timestamp) const;
    String reasonToString(SprayReason reason) const;

public:
    struct LogFileInfo {
        String path;
        uint16_t year = 0;
        uint8_t month = 0;
        uint32_t size = 0;
    };
    std::vector<LogFileInfo> collectLogFiles() const;
    String readLogChunk(const String& path, uint32_t offset, size_t maxSize, bool& isEOF) const;

    /**
     * Khởi tạo máy phun sương với chân điều khiển cụ thể.
     * @param pin Chân điều khiển máy phun sương.
     */
    SprayController(uint8_t pin, RTC_DS1307* rtc = nullptr, String pathFolderLog = "/logs/spray/", String pathFileNameSprayTotal="/datas/sprayDataTotal.bin")
        : _pin(pin), _rtc(rtc) {
        _pathFolderLog = normalizeFolderPath(pathFolderLog);
        _pathFileNameSprayTotal = pathFileNameSprayTotal;
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, LOW);
    }

    void setRtc(RTC_DS1307* rtc) {
        _rtc = rtc;
    }

    void setLogFolder(const String& pathFolderLog) {
        _pathFolderLog = normalizeFolderPath(pathFolderLog);
    }

    /**
     * Bật máy phun sương trong một khoảng thời gian nhất định.
     * @param durationMs Thời gian phun sương tính bằng mili giây.
     */
    void on(uint32_t durationMs, SprayReason reason = SPRAY_REASON_OTHER, const DateTime* timestamp = nullptr) {
        _durationMs = durationMs;
        _active = true;
        _startTime = millis();
        digitalWrite(_pin, HIGH);
        incrementSprayData(durationMs / 1000);
        logSprayEvent(durationMs, reason, timestamp);
    }

    /**
     * Tắt máy phun sương.
     */
    void off() {
        digitalWrite(_pin, LOW);
        _active = false;
    }

    /**
     * Kiểm tra trạng thái máy phun sương.
     * @return true nếu máy phun đang hoạt động, false nếu không.
     */
    bool isActive() const {
        return _active;
    }

    /**
     * Cập nhật trạng thái máy phun sương.
     * Phải được gọi liên tục trong vòng lặp chính.
     */
    void update() {
        if (_active && (millis() - _startTime >= _durationMs)) {
            off();
        }
    }

private:
    void saveSprayDataTotal() {
        JsonDocument doc;
        doc["totalSpraysCount"] = _sprayDataTotal.totalSpraysCount;
        doc["totalSprayDuration"] = _sprayDataTotal.totalSprayDuration;
        File file = LittleFS.open(_pathFileNameSprayTotal.c_str(), "w");
        serializeJson(doc, file);
        file.close();
    }

    void incrementSprayData(uint32_t durationSeconds) {
        _sprayDataTotal.totalSpraysCount += 1;
        _sprayDataTotal.totalSprayDuration += durationSeconds;
        saveSprayDataTotal();
    }

public:
    void loadSprayDataTotal() {
        Serial.print("Loading spray data total...");
        File file = LittleFS.open(_pathFileNameSprayTotal.c_str(), "r");
        if (!file) {
            Serial.println(" --> No spray data total file found. Using default values.");
            return;
        }

        JsonDocument doc;
        DeserializationError error = deserializeJson(doc, file);
        if (error) {
            Serial.print(" ---> Failed to read spray data total file: ");
            Serial.println(error.f_str());
            file.close();
            return;
        }

        _sprayDataTotal.totalSpraysCount = doc["totalSpraysCount"] | 0;
        _sprayDataTotal.totalSprayDuration = doc["totalSprayDuration"] | 0;

        file.close();
        Serial.println(" --> Spray data total loaded.");
    }

    SprayDataTotal getSprayDataTotal() {
        return _sprayDataTotal;
    }
};

inline void SprayController::logSprayEvent(uint32_t durationMs, SprayReason reason, const DateTime* timestamp) {
    DateTime effectiveTimestamp;
    bool hasTimestamp = resolveTimestamp(timestamp, effectiveTimestamp);

    ensureLogDirectoryExists();
    String logFilePath = hasTimestamp ? buildLogFilePath(effectiveTimestamp)
        : logDirWithSlash() + "log-unknown.log";

    File file = LittleFS.open(logFilePath.c_str(), "a");
    if (!file) {
        return;
    }

    String entry;
    if (hasTimestamp) {
        entry = formatTimestamp(effectiveTimestamp);
    }
    else {
        entry = "time=unknown";
    }

    entry += " | " + reasonToString(reason);
    entry += " | " + String(durationMs);

    file.println(entry);
    file.close();

    if (hasTimestamp) {
        enforceLogRetention();
    }
}

inline bool SprayController::resolveTimestamp(const DateTime* timestamp, DateTime& outTimestamp) const {
    if (timestamp) {
        outTimestamp = *timestamp;
        return true;
    }

    if (_rtc && _rtc->isrunning()) {
        outTimestamp = _rtc->now();
        return true;
    }

    return false;
}

inline String SprayController::normalizeFolderPath(const String& folder) const {
    String normalized = folder;
    if (normalized.length() == 0) {
        return "/";
    }

    if (!normalized.startsWith("/")) {
        normalized = "/" + normalized;
    }

    if (!normalized.endsWith("/")) {
        normalized += "/";
    }

    return normalized;
}

inline String SprayController::logDirWithSlash() const {
    if (_pathFolderLog.length() == 0) {
        return "/";
    }
    return _pathFolderLog;
}

inline String SprayController::logDirWithoutSlash() const {
    String folder = logDirWithSlash();
    if (folder.length() > 1 && folder.endsWith("/")) {
        folder.remove(folder.length() - 1);
    }
    return folder;
}

inline void SprayController::ensureLogDirectoryExists() {
    String dirPath = logDirWithoutSlash();
    if (dirPath == "/") {
        return;
    }
    if (!LittleFS.exists(dirPath.c_str())) {
        LittleFS.mkdir(dirPath.c_str());
    }
}

inline String SprayController::buildLogFilePath(const DateTime& timestamp) const {
    String path = logDirWithSlash();
    path += "log-";
    path += String(timestamp.month());
    path += "-";
    path += String(timestamp.year());
    path += ".log";
    return path;
}

inline String SprayController::formatTimestamp(const DateTime& timestamp) const {
    char buffer[25];
    snprintf(buffer, sizeof(buffer), "%02u-%02u-%04u %02u:%02u:%02u",
        timestamp.day(), timestamp.month(), timestamp.year(),
        timestamp.hour(), timestamp.minute(), timestamp.second());
    return String(buffer);
}

inline String SprayController::reasonToString(SprayReason reason) const {
    switch (reason) {
    case SPRAY_REASON_SCHEDULED:
        return "scheduled";
    case SPRAY_REASON_MANUAL_HARDWARE:
        return "hardware_button";
    case SPRAY_REASON_MANUAL_APP:
        return "app_button";
    case SPRAY_REASON_OTHER:
    default:
        return "other";
    }
}

inline bool SprayController::parseLogFileDate(const String& fileName, uint16_t& year, uint8_t& month) const {
    String baseName = fileName;
    int lastSlash = baseName.lastIndexOf('/');
    if (lastSlash >= 0) {
        baseName = baseName.substring(lastSlash + 1);
    }

    if (!baseName.startsWith("log-") || !baseName.endsWith(".log")) {
        return false;
    }

    int firstDash = baseName.indexOf('-');
    int secondDash = baseName.indexOf('-', firstDash + 1);
    int dotIndex = baseName.lastIndexOf('.');

    if (firstDash < 0 || secondDash < 0 || dotIndex < 0) {
        return false;
    }

    String monthStr = baseName.substring(firstDash + 1, secondDash);
    String yearStr = baseName.substring(secondDash + 1, dotIndex);

    uint8_t parsedMonth = static_cast<uint8_t>(monthStr.toInt());
    uint16_t parsedYear = static_cast<uint16_t>(yearStr.toInt());

    if (parsedMonth < 1 || parsedMonth > 12 || parsedYear < 2000) {
        return false;
    }

    month = parsedMonth;
    year = parsedYear;
    return true;
}

inline std::vector<SprayController::LogFileInfo> SprayController::collectLogFiles() const {
    std::vector<LogFileInfo> files;
    String dirPath = logDirWithoutSlash();
    Dir dir = LittleFS.openDir(dirPath.c_str());

    while (dir.next()) {
        String name = dir.fileName();
        uint16_t year;
        uint8_t month;
        if (!parseLogFileDate(name, year, month)) {
            continue;
        }

        LogFileInfo info;
        if (name.startsWith("/")) {
            info.path = name;
        }
        else {
            String baseName = name;
            int slashIndex = baseName.lastIndexOf('/');
            if (slashIndex >= 0) {
                baseName = baseName.substring(slashIndex + 1);
            }
            info.path = logDirWithSlash() + baseName;
        }
        info.year = year;
        info.month = month;
        info.size = dir.fileSize();
        files.push_back(info);
    }

    return files;
}

inline void SprayController::enforceLogRetention() {
    auto files = collectLogFiles();
    if (files.size() <= MAX_LOG_FILES) {
        return;
    }

    std::sort(files.begin(), files.end(), [](const LogFileInfo& a, const LogFileInfo& b) {
        if (a.year == b.year) {
            return a.month < b.month;
        }
        return a.year < b.year;
        });
    size_t filesToRemove = files.size() - MAX_LOG_FILES;
    for (size_t i = 0; i < filesToRemove; ++i) {
        LittleFS.remove(files[i].path.c_str());
    }
}

inline String SprayController::readLogChunk(const String& path, uint32_t offset, size_t maxSize, bool& isEOF) const {
    isEOF = true;
    
    File file = LittleFS.open(path.c_str(), "r");
    if (!file) {
        return "";
    }
    
    if (offset >= file.size()) {
        file.close();
        return "";
    }
    
    file.seek(offset);
    
    size_t bytesToRead = file.size() - offset;
    if (bytesToRead > maxSize) {
        bytesToRead = maxSize;
        isEOF = false;
    }
    
    String chunk;
    if (bytesToRead > 0) {
        char* buffer = new char[bytesToRead + 1];
        size_t actualRead = file.readBytes(buffer, bytesToRead);
        buffer[actualRead] = '\0';
        chunk = String(buffer);
        delete[] buffer;
    }
    
    file.close();
    return chunk;
}

#endif