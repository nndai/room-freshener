#ifndef REQUEST_WIFI_INET_H
#define REQUEST_WIFI_INET_H

#include <Arduino.h>
#include <ArduinoJson.h>

#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266Ping.h>

class WifiInetManager {
    bool running = false;

    int internet_check_interval = 5;
    unsigned long time_login = 0;
    bool is_internet_connected = false;

    // consecutive failures of checkInternet()
    int consecutive_failures = 0;

    enum State {
        STATE_STOPPED,
        STATE_WAIT_WIFI,
        STATE_IDLE_WAIT,
        STATE_CHECK_INTERNET_MAIN,
        STATE_DO_LOGIN,
        STATE_CHECK_INTERNET_POST_LOGIN,
        STATE_VERIFY_INTERNET_LOOP
    };

    State current_state = STATE_STOPPED;
    unsigned long wait_start_time = 0;
    unsigned long delay_amount = 0;
    int verify_attempt = 0;

    IPAddress ip_ping = IPAddress(203, 162, 4, 191);

    bool checkInternet() {
        return Ping.ping(ip_ping, 1);
    }

    String urlEncode(const String& str) {
        String encoded = "";
        for (size_t i = 0; i < str.length(); ++i) {
            char c = str[i];
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded += c;
            }
            else if (c == ' ') {
                encoded += '+';
            }
            else {
                char buf[4];
                sprintf(buf, "%%%02X", (uint8_t)c);
                encoded += String(buf);
            }
        }
        return encoded;
    }

    String processInterstitialHtml(const String& html) {
        int idPos = html.indexOf("id=\"authForm\"");
        if (idPos == -1) return String();

        int formStart = html.lastIndexOf("<form", idPos);
        if (formStart == -1) return String();
        int formEnd = html.indexOf("</form>", formStart);
        if (formEnd == -1) formEnd = html.length();

        String form = html.substring(formStart, formEnd);

        String query = "";
        const char* ids[] = { "serial", "client_mac", "client_ip", "userurl", "login_url", "chap-id", "chap-challenge" };
        const char* names[] = { "serial", "client_mac", "client_ip", "userurl", "login_url", "chap_id", "chap_challenge" };

        for (int i = 0; i < 7; i++) {
            String searchId = String("id=\"") + ids[i] + "\"";
            int ipos = form.indexOf(searchId);
            if (ipos != -1) {
                int inputStart = form.lastIndexOf("<input", ipos);
                if (inputStart != -1 || ipos - inputStart < 150) {
                    int valPos = form.indexOf("value=\"", (inputStart != -1 ? inputStart : ipos));
                    if (valPos != -1) {
                        int vstart = valPos + 7;
                        int vend = form.indexOf("\"", vstart);
                        if (vend != -1) {
                            String value = form.substring(vstart, vend);
                            if (query.length() > 0) query += "&";
                            query += String(names[i]) + "=" + urlEncode(value);
                        }
                    }
                    else {
                        if (query.length() > 0) query += "&";
                        query += String(names[i]) + "=";
                    }
                }
            }
        }

        String final_url = "http://v1.awingconnect.vn/login";
        if (query.length() > 0) {
            final_url += "?" + query;
        }
        return final_url;
    }

    bool doLogin() {
        WiFiClient client;
        HTTPClient http;
        http.setTimeout(2000);

        Serial.println("Logging in WIFI INET...");

        String gw = WiFi.gatewayIP().toString();
        if (gw == "0.0.0.0" || gw.length() == 0) {
            Serial.println("---> Get Router IP failed.");
            return false;
        }

        String gateway_url = "http://" + gw + "/login?r=1";
        http.begin(client, gateway_url);
        http.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        int code = http.GET();
        if (code <= 0) {
            Serial.println("[-] Lỗi kết nối Router: " + String(code));
            http.end();
            return false;
        }
        String resp = http.getString();
        http.end();

        String redirect_url = processInterstitialHtml(resp);
        if (redirect_url.length() == 0 || redirect_url == "http://v1.awingconnect.vn/login") {
            Serial.println("---> Không tìm thấy form đăng nhập hoặc URL Redirect.");
            return false;
        }

        Serial.println("Redirect URL: " + redirect_url);

        http.begin(client, "http://v1.awingconnect.vn/Home/VerifyUrl");
        http.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        http.addHeader("Referer", redirect_url);
        http.addHeader("X-Requested-With", "XMLHttpRequest");
        http.addHeader("Content-Type", "application/json");

        int httpCode = http.POST("");
        if (httpCode != 200) {
            Serial.println("Verify post failed: " + String(httpCode));
            http.end();
            return false;
        }
        String verifyResp = http.getString();
        http.end();

        JsonDocument doc;
        DeserializationError error = deserializeJson(doc, verifyResp);
        if (error) {
            Serial.println("JSON Decode Exception");
            return false;
        }

        String html_form = doc["captiveContext"]["contentAuthenForm"].as<String>();
        if (html_form.isEmpty()) return false;

        int actionIndex = html_form.indexOf("action=\"");
        if (actionIndex == -1) return false;
        int start = actionIndex + 8;
        int end = html_form.indexOf('"', start);
        String real_action_url = html_form.substring(start, end);

        int userIndex = html_form.indexOf("name=\"username\" value=\"");
        if (userIndex == -1) return false;
        start = userIndex + 23;
        end = html_form.indexOf('"', start);
        String real_user = html_form.substring(start, end);

        int passIndex = html_form.indexOf("name=\"password\" value=\"");
        if (passIndex == -1) return false;
        start = passIndex + 23;
        end = html_form.indexOf('"', start);
        String real_pass = html_form.substring(start, end);

        http.begin(client, real_action_url);
        http.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        http.addHeader("Content-Type", "application/x-www-form-urlencoded");

        String final_payload = "username=" + urlEncode(real_user) + "&password=" + urlEncode(real_pass) + "&dst=" + urlEncode("http://v1.awingconnect.vn/Success") + "&popup=false";
        httpCode = http.POST(final_payload);
        http.end();

        if (httpCode == 200 || httpCode == 302) {
            Serial.println("[SUCCESS] Login WiFi INET successful!");
            return true;
        }
        Serial.println("[FAILED] Login WiFi INET failed. HTTP Code: " + String(httpCode));
        return false;
    }

    void errorLoginHandle() {
        Serial.println("[ERROR] Failed to login!");
    }

    void setWait(unsigned long ms, State next_state) {
        delay_amount = ms;
        wait_start_time = millis();
        current_state = next_state;
    }

    bool isWaiting() {
        if (delay_amount > 0) {
            if (millis() - wait_start_time < delay_amount) {
                return true;
            }
            delay_amount = 0;
        }
        return false;
    }


public:
    WifiInetManager() {
    }

    void start() {
        running = true;
        Serial.println("Starting WiFi INET Manager...");
        current_state = STATE_CHECK_INTERNET_MAIN;
        delay_amount = 0;
        time_login = 0;
        consecutive_failures = 0;
    }

    void stop() {
        running = false;
        Serial.println("Stopped WiFi INET Manager...");
        current_state = STATE_STOPPED;
    }

    bool isWifiInet() {
        if (WiFi.status() != WL_CONNECTED) {
            return false;
        }

        String ssid = WiFi.SSID();
        if (ssid.length() == 0) {
            return false;
        }

        ssid.toLowerCase();
        if (ssid.indexOf("inet") != -1) {
            return true;
        }
        return false;
    }

    /**
     * @brief Loop function to check internet connection and login
     *
     * This function should be called in the main loop of the application
     */
    void loop() {
        if (!running) {
            if (current_state != STATE_STOPPED) {
                current_state = STATE_STOPPED;
            }
            return;
        }

        if (!isWifiInet()) {
            time_login = 0;
            is_internet_connected = false;
            consecutive_failures = 0;
            current_state = STATE_WAIT_WIFI;
            return;
        }

        if (isWaiting()) {
            return;
        }

        switch (current_state) {
        case STATE_STOPPED:
        case STATE_WAIT_WIFI:
            current_state = STATE_CHECK_INTERNET_MAIN;
            break;

        case STATE_IDLE_WAIT:
            setWait(internet_check_interval * 1000, STATE_CHECK_INTERNET_MAIN);
            break;

        case STATE_CHECK_INTERNET_MAIN:
            if (checkInternet()) {
                consecutive_failures = 0;
                is_internet_connected = true;
                if (time_login == 0) time_login = millis();
                current_state = STATE_IDLE_WAIT;
            }
            else {
                consecutive_failures++;
                Serial.println("Internet check failed (" + String(consecutive_failures) + "/3)");
                if (consecutive_failures >= 3) {
                    is_internet_connected = false;
                    current_state = STATE_DO_LOGIN;
                }
                else {
                    // schedule another check after the usual interval; stay in same state
                    setWait(3000, STATE_CHECK_INTERNET_MAIN);
                }
            }
            break;

        case STATE_DO_LOGIN: {
            bool is_login_success = doLogin();
            if (is_login_success) {
                time_login = millis();
                current_state = STATE_CHECK_INTERNET_POST_LOGIN;
            }
            else {
                errorLoginHandle();
                if (!running) {
                    current_state = STATE_STOPPED;
                }
                else {
                    setWait(internet_check_interval * 1000, STATE_CHECK_INTERNET_MAIN);
                }
            }
            break;
        }

        case STATE_CHECK_INTERNET_POST_LOGIN:
            if (checkInternet()) {
                is_internet_connected = true;
                if (time_login == 0) time_login = millis();
                current_state = STATE_IDLE_WAIT;
            }
            else {
                Serial.println("Status: Login OK but no inet...");
                setWait(3000, STATE_VERIFY_INTERNET_LOOP);
                verify_attempt = 0;
            }
            break;

        case STATE_VERIFY_INTERNET_LOOP:
            if (verify_attempt < 15) {
                Serial.println("Checking... Attempt: " + String(verify_attempt + 1) + "/15");
                if (checkInternet()) {
                    is_internet_connected = true;
                    current_state = STATE_IDLE_WAIT;
                }
                else {
                    verify_attempt++;
                    setWait(200, STATE_VERIFY_INTERNET_LOOP);
                }
            }
            else {
                Serial.println("Status: Error (Login OK but no inet. Relogin)");
                time_login = 0;
                setWait(internet_check_interval * 1000, STATE_CHECK_INTERNET_MAIN);
            }
            break;
        }
    }

    bool isRunning() { return running; }
    bool isInternetConnected() { return is_internet_connected; }
    bool forceLogin(){ return doLogin(); }

    static WifiInetManager& getInstance() {
        static WifiInetManager instance;
        return instance;
    }
};

// Easy-to-use global helper functions:
inline void startWifiInetLogin() {
    WifiInetManager::getInstance().start();
}

inline void stopWifiInetLogin() {
    WifiInetManager::getInstance().stop();
}

inline void loopWifiInetLogin() {
    WifiInetManager::getInstance().loop();
}

inline bool isWifiInetRunning() {
    return WifiInetManager::getInstance().isRunning();
}

inline bool isWifiInetConnected() {
    return WifiInetManager::getInstance().isWifiInet();
}

inline bool isInternetWifiInetConnected() {
    return WifiInetManager::getInstance().isInternetConnected();
}

inline bool forceWifiInetLogin() {
    return WifiInetManager::getInstance().forceLogin();
}

#endif