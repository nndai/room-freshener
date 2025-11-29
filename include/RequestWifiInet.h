#ifndef REQUEST_WIFI_INET_H
#define REQUEST_WIFI_INET_H

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>

bool isWifiInetConnected() {
    if(WiFi.getMode() != WIFI_STA && WiFi.getMode() != WIFI_AP_STA) {
        return false;
    }
    if (WiFi.status() != WL_CONNECTED) {
        return false;
    }
    String ssid = WiFi.SSID();
    ssid.toUpperCase();
    return ssid.indexOf("INET") != -1;
}

bool logoutWifiInet() {
    WiFiClient client;
    HTTPClient http;

    http.begin(client, "http://" + WiFi.gatewayIP().toString() + "/logout?");
    int httpCode = http.GET();

    Serial.println("Logout: " + String(httpCode));
    http.end();
    return httpCode > 0 && httpCode < 400;
}

bool loginWifiInet() {
    WiFiClient client;
    HTTPClient http;

    http.begin(client, "http://" + WiFi.gatewayIP().toString() + "/login");
    String body = "username=awing15-15&password=Awing15-15@2023";
    int httpCode = http.POST(body);

    Serial.println("Login: " + String(httpCode));
    http.end();
    return httpCode > 0 && httpCode < 400;
}

#endif