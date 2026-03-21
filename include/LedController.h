#ifndef LEDCONTROLLER_H
#define LEDCONTROLLER_H

#include <Arduino.h>

/**
 * @brief Lớp điều khiển đèn LED với các chế độ bật, tắt và nhấp nháy.
 */
class LedController {
private:
    enum LedState {
        OFF = 0,
        ON,
        BLINK,
        BLINK_N,
    };

private:
    uint8_t _pin;               /// Chân điều khiển đèn LED
    uint8_t _isOn;              /// Trạng thái đèn LED (bật/tắt)
    uint8_t _activeLow;         /// Thiết lập nếu đèn LED hoạt động ở mức thấp (LOW)
    uint32_t _blinkInterval;    /// Khoảng thời gian nhấp nháy tính bằng mili giây
    uint32_t _lastToggleTime;   /// Thời gian lần cuối cùng trạng thái đèn LED được thay đổi

    LedState _state;
    uint8_t _blinkCount;
    uint8_t _blinkIndex;
    uint32_t _onInterval;
    uint32_t _offInterval;

public:
    /**
     * @brief Khởi tạo đối tượng LedController.
     * @param pin Chân điều khiển đèn LED.
     * @param activeLow Thiết lập nếu đèn LED hoạt động ở mức thấp (LOW).
     */
    LedController(uint8_t pin, bool activeLow = true)
        : _pin(pin), _isOn(0), _activeLow(activeLow), _blinkInterval(0), _lastToggleTime(0),
        _state(OFF), _blinkCount(0), _blinkIndex(0), _onInterval(0), _offInterval(0) {
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, _activeLow ? HIGH : LOW);
    }

    /**
     * @brief Thiết lập chế độ nhấp nháy cho đèn LED.
     * @param intervalMs Khoảng thời gian nhấp nháy tính bằng mili giây.
     */
    void blink(uint32_t intervalMs) {
        if (_state == BLINK && intervalMs == _blinkInterval)
            return;
        _blinkInterval = intervalMs;
        _state = BLINK;
        _lastToggleTime = millis();
    }

    /**
     * @brief Thiết lập chế độ nhấp nháy cho đèn LED theo chuỗi.
     *
     * LED sẽ nháy một số lần nhất định (blinkCount) với khoảng thời gian bật/tắt (onIntervalMs),
     * sau khi nháy xong chuỗi, LED sẽ tắt và nghỉ (offIntervalMs) trước khi bắt đầu chuỗi tiếp theo.
     *
     * @param blinkCount Số lần nháy trong mỗi chuỗi.
     * @param onIntervalMs Thời gian LED bật hoặc tắt trong mỗi nháy (mili giây).
     * @param offIntervalMs Thời gian nghỉ giữa các chuỗi nháy (mili giây).
     */

    void blink(uint8_t blinkCount, uint32_t onIntervalMs, uint32_t offIntervalMs) {
        if (_state == BLINK_N) {
            if (blinkCount == _blinkCount && onIntervalMs == _onInterval && offIntervalMs == _offInterval)
                return;
        }
        _state = BLINK_N;
        _blinkCount = blinkCount;
        _blinkIndex = 0;
        _onInterval = onIntervalMs;
        _offInterval = offIntervalMs;
        _lastToggleTime = millis();
    }

    /**
     * @brief Bật đèn LED.
     */
    void on() {
        _state = ON;
        _isOn = 1;
        _blinkInterval = 0;
        digitalWrite(_pin, _activeLow ? LOW : HIGH);
    }

    /**
     * @brief Tắt đèn LED.
     */
    void off() {
        _state = OFF;
        _isOn = 0;
        _blinkInterval = 0;
        digitalWrite(_pin, _activeLow ? HIGH : LOW);
    }

    /**
     * @brief Cập nhật trạng thái đèn LED.
     * @note Phải được gọi trong vòng lặp chính.
     */
    void update() {
        uint32_t currentTime = millis();
        if (_state == BLINK_N) {
            if (_blinkIndex < _blinkCount) {
                if (!_isOn && currentTime - _lastToggleTime >= _onInterval) {
                    _isOn = true;
                    digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                    _lastToggleTime = currentTime;
                }
                else if (_isOn && currentTime - _lastToggleTime >= _onInterval) {
                    _isOn = false;
                    digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                    _lastToggleTime = currentTime;
                    _blinkIndex++;
                }
            }
            else {
                if (currentTime - _lastToggleTime >= _offInterval) {
                    _blinkIndex = 0;
                }
            }
        }
        else if (_state == BLINK) {
            if (currentTime - _lastToggleTime >= _blinkInterval) {
                _isOn = !_isOn;
                digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                _lastToggleTime = currentTime;
            }
        }
    }
};


#endif
