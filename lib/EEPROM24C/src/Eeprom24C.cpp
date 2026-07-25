#include <Eeprom24C.h>

Eeprom24C::Eeprom24C(TwoWire* wire, uint32_t sizeMemory, uint8_t deviceAddress) {
    if(_i2c == nullptr){
        _i2c = &Wire;
    }
    else {
        _i2c = wire;
    }
    _deviceAddress = deviceAddress;
    _sizeMemory = sizeMemory;
    _sizeBytes = sizeMemory * 128;
}

uint32_t Eeprom24C::getSize() {
    return _sizeBytes;
}

void Eeprom24C::write(uint16_t address, uint8_t data) {
    if ((address >= _sizeBytes) || (read(address) == data)) {
        return;
    }
    if (_sizeMemory <= 2) {
        _i2c->beginTransmission(_deviceAddress);
        _i2c->write((uint8_t)address);
    }
    else if (_sizeMemory <= 16) {
        _i2c->beginTransmission((uint8_t)(_deviceAddress | ((address >> 8) & 0x07)));
        _i2c->write(address & 0xFF);
    }
    else if (_sizeMemory <= 256) {
        _i2c->beginTransmission(_deviceAddress);
        _i2c->write(address >> 8);
        _i2c->write(address & 0xFF);
    }

    _i2c->write(data);
    _i2c->endTransmission();
    delay(5);
}

uint8_t Eeprom24C::read(uint16_t address) {
    if (address >= _sizeBytes) {
        return 0;
    }

    uint8_t data = 0;
    if (_sizeMemory <= 2) {
        _i2c->beginTransmission(_deviceAddress);
        _i2c->write(address);
        _i2c->endTransmission();
        _i2c->requestFrom(_deviceAddress, (uint8_t)1);
    }
    else if (_sizeMemory <= 16) {
        _i2c->beginTransmission((uint8_t)(_deviceAddress | ((address >> 8) & 0x07)));
        _i2c->write(address & 0xFF);
        _i2c->endTransmission();
        _i2c->requestFrom((uint8_t)(_deviceAddress | ((address >> 8) & 0x07)), (uint8_t)1);
    }
    else if (_sizeMemory <= 256) {
        _i2c->beginTransmission(_deviceAddress);
        _i2c->write(address >> 8);
        _i2c->write(address & 0xFF);
        _i2c->endTransmission();
        _i2c->requestFrom(_deviceAddress, (uint8_t)1);
    }
    if (_i2c->available()) {
        data = _i2c->read();
        //delay(10);
        return data;
    }
    return 0;
}

void Eeprom24C::write(uint16_t address, const void* data, uint8_t size) {
    const uint8_t* byteData = static_cast<const uint8_t*>(data);
    for (uint8_t i = 0; i < size; ++i) {
        write(address + i, byteData[i]);
    }
}

void Eeprom24C::read(uint16_t address, void* data, uint8_t size) {
    uint8_t* byteData = static_cast<uint8_t*>(data);
    for (uint8_t i = 0; i < size; ++i) {
        byteData[i] = read(address + i);
    }
}

void Eeprom24C::writeString(uint16_t address, const String& data) {
    uint8_t len = data.length();

    write(address, &len, 1);
    write(address + 1, data.c_str(), len + 1);
}

void Eeprom24C::readString(uint16_t address, String& data) {
    uint8_t len = 0;
    read(address, &len, 1);
    if (len == 0) return;

    char* str = new char[len + 1];
    str[len] = '\0';
    read(address + 1, str, len);
    data = String(str);
    if (str != NULL) delete[] str;
}
