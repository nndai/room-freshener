#ifndef _EEPROM24CXX_H
#define _EEPROM24CXX_H

#include <Wire.h>
#include <Arduino.h>

class Eeprom24C {
private:
    TwoWire* _i2c;
    uint32_t _sizeMemory;
    uint32_t _sizeBytes;
    uint8_t _deviceAddress;


public:
    /**
     * @brief Construct a new Eeprom24C object.
     * @param wire Pointer to the TwoWire (I2C) instance to use. Defaults to &Wire.
     * @param sizeMemory Size of the EEPROM in kilobits (e.g., 2 for 24C02, 16 for 24C16). Defaults to 2.
     * @param deviceAddress I2C device address. Defaults to 0x50.
     */
    Eeprom24C(TwoWire* wire = &Wire, uint32_t sizeMemory = 2, uint8_t deviceAddress = 0x50);

    /**
     * @brief Get the size of the EEPROM in bytes.
     * @return Size in bytes.
    */
    uint32_t getSize();

    /**
     * @brief Write a single byte to the specified address in EEPROM.
     * @param address The address to write to.
     * @param data The byte of data to write.
     */
    void write(uint16_t address, uint8_t data);

    /**
     * @brief Read a single byte from the specified address in EEPROM.
     * @param address The address to read from.
     * @return The byte of data read from the specified address.
     */
    uint8_t read(uint16_t address);

    /**
     * @brief Read multiple bytes from EEPROM starting at the specified address.
     * @param address The starting address to read from.
     * @param data Pointer to the buffer where the read data will be stored.
     * @param size Number of bytes to read.
     */
    void read(uint16_t address, void* data, uint8_t size);

    /**
     * @brief Write multiple bytes to EEPROM starting at the specified address.
     * @param address The starting address to write to.
     * @param data Pointer to the buffer containing the data to be written.
     * @param size Number of bytes to write.
     */
    void write(uint16_t address, const void* data, uint8_t size);

    /**
     * @brief Read a string from EEPROM starting at the specified address.
     * @param address The starting address to read from.
     * @param data Reference to a String object where the read string will be stored.
     * @note The string is stored with a length prefix (1 uint8_t) followed by the characters.
     */
    void readString(uint16_t address, String& data);

    /**
     * @brief Write a string to EEPROM starting at the specified address.
     * @param address The starting address to write to.
     * @param data The String object containing the string to be written.
     * @note The string is stored with a length prefix (1 uint8_t) followed by the characters.
     */
    void writeString(uint16_t address, const String& data);

};

#endif // _EEPROM24CXX_H