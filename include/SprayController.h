#ifndef SPRAYCONTROLLER_H
#define SPRAYCONTROLLER_H

#include <Arduino.h>

/**
 * Class điều khiển máy phun sương.
 */
class SprayController {
    uint8_t _pin;
    bool _active = false;
    uint32_t _durationMs = 0;
    uint32_t _startTime = 0;

public:
    /**
     * Khởi tạo máy phun sương với chân điều khiển cụ thể.
     * @param pin Chân điều khiển máy phun sương.
     */
    SprayController(uint8_t pin) : _pin(pin) {
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, LOW);
    }
    
    /**
     * Bật máy phun sương trong một khoảng thời gian nhất định.
     * @param durationMs Thời gian phun sương tính bằng mili giây.
     */
    void on(uint32_t durationMs) {
        _durationMs = durationMs;
        _active = true;
        _startTime = millis();
        digitalWrite(_pin, HIGH);
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
};
#endif