#ifndef LEDCONTROLLER_H
#define LEDCONTROLLER_H

#include <Arduino.h>

/**
 * @brief Lớp điều khiển đèn LED với các chế độ bật, tắt và nhấp nháy.
 */
class LedController {
private:
    uint8_t _pin;               /// Chân điều khiển đèn LED
    uint8_t _isOn;              /// Trạng thái đèn LED (bật/tắt)
    uint8_t _activeLow;         /// Thiết lập nếu đèn LED hoạt động ở mức thấp (LOW)
    uint32_t _blinkInterval;    /// Khoảng thời gian nhấp nháy tính bằng mili giây
    uint32_t _lastToggleTime;   /// Thời gian lần cuối cùng trạng thái đèn LED được thay đổi

public:
    /**
     * @brief Khởi tạo đối tượng LedController.
     * @param pin Chân điều khiển đèn LED.
     * @param activeLow Thiết lập nếu đèn LED hoạt động ở mức thấp (LOW).
     */
    LedController(uint8_t pin, bool activeLow = true)
        : _pin(pin), _isOn(0), _activeLow(activeLow), _blinkInterval(0), _lastToggleTime(0) {
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, _activeLow ? HIGH : LOW);
    }

    /**
     * @brief Thiết lập chế độ nhấp nháy cho đèn LED.
     * @param intervalMs Khoảng thời gian nhấp nháy tính bằng mili giây.
     */
    void blink(uint32_t intervalMs) {
        _blinkInterval = intervalMs;
        _lastToggleTime = millis();
    }

    /**
     * @brief Bật đèn LED.
     */
    void on() {
        _isOn = 1;
        _blinkInterval = 0;
        digitalWrite(_pin, _activeLow ? LOW : HIGH);
    }

    /**
     * @brief Tắt đèn LED.
     */
    void off() {
        _isOn = 0;
        _blinkInterval = 0;
        digitalWrite(_pin, _activeLow ? HIGH : LOW);
    }

    /**
     * @brief Cập nhật trạng thái đèn LED.
     * @note Phải được gọi trong vòng lặp chính.
     */
    void update() {
        if (_blinkInterval > 0) {
            uint32_t currentTime = millis();
            if (currentTime - _lastToggleTime >= _blinkInterval) {
                _isOn = !_isOn;
                digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                _lastToggleTime = currentTime;
            }
        }
    }
};


#endif
