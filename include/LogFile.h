#ifndef LOGFILE_H
#define LOGFILE_H

#include <Arduino.h>
#include <LittleFS.h>

/**
 * Class quản lý file log trên hệ thống file LittleFS.
 */
class LogFile {
    String _filename;
    File _file;

public:
    /**
     * Khởi tạo LogFile với tên file cụ thể.
     * @param filename Tên file log.
     */
    LogFile(const String &filename) : _filename(filename) {}

    /**
     * Bắt đầu ghi log vào file.
     * @return true nếu mở file thành công, false nếu thất bại.
     */
    bool begin() {
        if (!LittleFS.begin()) {
            return false;
        }
        _file = LittleFS.open(_filename, "a");
        return _file;
    }

    /**
     * Ghi một dòng log vào file.
     * @param message Nội dung log.
     */
    void log(const String &message) {
        if (_file) {
            _file.seek(0, SeekEnd);
            _file.println(message);
            _file.flush();
        }
    }

    /**
     * Kết thúc ghi log và đóng file.
     */
    void end() {
        if (_file) {
            _file.close();
        }
        LittleFS.end();
    }
};

#endif