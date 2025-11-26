import asyncio
import websockets
import json
import base64
import struct
import time
import random
from datetime import datetime
# Cấu hình
PORT = 82
HOST = "192.168.137.1"


# --- GIẢ LẬP DỮ LIỆU BỘ NHỚ ---

# 1. Danh sách Task
STRUCT_FORMAT = '<BBBB?xxxI' 
tasks = [] 
next_task_id = 1

# 2. Dữ liệu WiFi Config (Giả lập bộ nhớ EEPROM/LittleFS)
wifi_config = {
    "ssidAp": "ESP8266_Mock_AP",
    "passwordAp": "12345678",
    "ssid": "MyWiFi",
    "password": "mypassword",
    "modeConnect": 0  # 0: WEBSOCKET, 1: BLYNK, 2: MQTT
}

# 3. Dữ liệu thống kê (Home Data)
home_data = {
    "temperature": 29.3,       
    "humidity": 75.5,
    "totalSprayCount": 15,
    "totalSprayDuration": 75000, # ms
    
    # Last Spray info
    "last_hour": 10,
    "last_minute": 30,
    "last_duration": 5000,
    "last_reason": 2 # 1: Manual, 2: Scheduled
}

# --- HÀM HỖ TRỢ ---

def create_task(hour, minute, weekday, duration, enabled=True, task_id=None):
    global next_task_id
    if task_id is None:
        tid = next_task_id
        next_task_id += 1
        if next_task_id >= 255: next_task_id = 1
    else:
        tid = task_id
    
    return {
        "id": tid,
        "hour": hour,
        "minute": minute,
        "weekday": weekday,
        "enabled": enabled,
        "duration": duration
    }

# Tạo vài task mẫu
tasks.append(create_task(8, 0, 127, 5000, True))
tasks.append(create_task(17, 30, 62, 3000, True))

def get_next_spray_info():
    now = datetime.now()
    cur_h = now.hour
    cur_m = now.minute
    
    candidate = None
    sorted_tasks = sorted(tasks, key=lambda x: x['hour'] * 60 + x['minute'])
    found_today = False
    
    # 1. Tìm trong ngày hôm nay
    for t in sorted_tasks:
        if not t['enabled']: continue
        if (t['hour'] * 60 + t['minute']) > (cur_h * 60 + cur_m):
            candidate = t
            found_today = True
            break
    
    # 2. Tìm ngày mai
    if not found_today:
        for t in sorted_tasks:
            if t['enabled']:
                candidate = t
                break
    
    if candidate:
        return {"hour": candidate['hour'], "minute": candidate['minute'], "duration": candidate['duration']}
    else:
        return {"hour": 0, "minute": 0, "duration": 0}

# --- XỬ LÝ WEBSOCKET ---

async def handler(websocket):
    client_ip = websocket.remote_address[0]
    print(f"[Client] Đã kết nối từ {client_ip}")
    
    try:
        async for message in websocket:
            print(f"\n[Nhận] {message}")
            try:
                doc = json.loads(message)
                command = doc.get("command", "")
                response = None
                should_reboot = False

                # ==========================================
                # XỬ LÝ LỆNH
                # ==========================================

                if command == "sprayNow":
                    duration = doc.get("duration", 0)
                    print(f"-> [THỰC THI] Phun sương: {duration}ms")
                    
                    now = datetime.now()
                    home_data["last_hour"] = now.hour
                    home_data["last_minute"] = now.minute
                    home_data["last_duration"] = duration
                    home_data["last_reason"] = 1 # Manual
                    home_data["totalSprayCount"] += 1
                    home_data["totalSprayDuration"] += duration

                elif command == "getHomeData":
                    temp_jitter = random.uniform(-0.5, 0.5)
                    hum_jitter = random.uniform(-1.0, 1.0)
                    next_spray = get_next_spray_info()

                    response = {
                        "command": "getHomeDataResponse",
                        "temperature": round(home_data["temperature"] + temp_jitter, 1),
                        "humidity": round(home_data["humidity"] + hum_jitter, 1),
                        
                        "lastSprayHourTime": home_data["last_hour"],
                        "lastSprayMinuteTime": home_data["last_minute"],
                        "lastSprayDurationMs": home_data["last_duration"],
                        "lastSprayReason": home_data["last_reason"],
                        
                        "nextSprayHourTime": next_spray["hour"],
                        "nextSprayMinuteTime": next_spray["minute"],
                        "nextSprayDurationMs": next_spray["duration"],
                        
                        "totalSprayCount": home_data["totalSprayCount"],
                        "totalSprayDuration": home_data["totalSprayDuration"]
                    }
                    print("-> Gửi Home Data")

                elif command == "setWiFiConfig":
                    ssidAp = doc.get("ssidAp", "")
                    passAp = doc.get("passwordAp", "")
                    ssid = doc.get("ssid", "")
                    pwd = doc.get("password", "")
                    mode = doc.get("modeConnect", 0)

                    # Logic validate giống C++
                    if len(ssidAp) > 32 or len(passAp) > 64 or len(ssid) > 32 or len(pwd) > 64 or mode > 2:
                        print("-> [Lỗi] Config Wifi không hợp lệ")
                        response = {
                            "command": "setWiFiConfigResponse",
                            "status": False,
                            "message": "Invalid parameters."
                        }
                    else:
                        print(f"-> [CONFIG] Lưu Wifi: AP={ssidAp}, STA={ssid}, Mode={mode}")
                        wifi_config["ssidAp"] = ssidAp
                        wifi_config["passwordAp"] = passAp
                        wifi_config["ssid"] = ssid
                        wifi_config["password"] = pwd
                        wifi_config["modeConnect"] = mode
                        
                        response = {
                            "command": "setWiFiConfigResponse",
                            "status": True,
                            "message": "WiFi configuration updated. Rebooting..."
                        }
                        should_reboot = True

                elif command == "getTime":
                    response = {"command": "getTimeResponse", "timestamp": int(time.time())}

                elif command == "setTime":
                    new_ts = doc.get("timestamp", 0)
                    print(f"-> Sync Time: {new_ts}")
                    response = {"command": "setTimeResponse", "status": True, "message": f"RTC updated: {new_ts}"}

                elif command == "addTaskSpray":
                    new_task = create_task(doc.get("hour"), doc.get("minute"), doc.get("weekday"), doc.get("duration"), doc.get("enabled"))
                    tasks.append(new_task)
                    print(f"-> Thêm Task ID {new_task['id']}")
                    response = {"command": "addTaskSprayResponse", "status": True, "taskId": new_task['id'], "message": "success."}

                elif command == "removeTaskSpray":
                    tid = doc.get("taskId", 0)
                    initial_len = len(tasks)
                    tasks[:] = [t for t in tasks if t['id'] != tid]
                    success = len(tasks) < initial_len
                    response = {"command": "removeTaskSprayResponse", "status": success, "message": "success." if success else "Not found"}

                elif command == "setTaskEnabled":
                    tid = doc.get("task_id", 0); en = doc.get("enabled", False)
                    found = False
                    for t in tasks:
                        if t['id'] == tid: t['enabled'] = en; found = True; break
                    response = {"command": "setTaskEnabledResponse", "status": found, "message": "success." if found else "Not found"}

                elif command == "editTaskSpray":
                    tid = doc.get("taskId", 0)
                    found = False
                    for t in tasks:
                        if t['id'] == tid:
                            t['hour'] = doc.get("hour", t['hour'])
                            t['minute'] = doc.get("minute", t['minute'])
                            t['weekday'] = doc.get("weekday", t['weekday'])
                            t['duration'] = doc.get("duration", t['duration'])
                            t['enabled'] = doc.get("enabled", t['enabled'])
                            found = True; break
                    response = {"command": "editTaskSprayResponse", "status": found, "message": "success." if found else "Not found"}

                elif command == "getAllTaskSpray":
                    binary_data = bytearray()
                    for t in tasks:
                        packed = struct.pack(STRUCT_FORMAT, t['id'], t['hour'], t['minute'], t['weekday'], t['enabled'], t['duration'])
                        binary_data.extend(packed)
                    b64_str = base64.b64encode(binary_data).decode('utf-8') if binary_data else ""
                    response = {"command": "getAllTasksSprayResponse", "countask": len(tasks), "sizeTask": struct.calcsize(STRUCT_FORMAT), "dataTask": b64_str}
                    print(f"-> Gửi {len(tasks)} tasks")

                else:
                    print(f"-> Unknown: {command}")
                    response = {"command": "unknownCommandResponse", "status": False, "message": "Unknown command."}

                # Gửi phản hồi
                if response:
                    await websocket.send(json.dumps(response))
                
                # Xử lý Reboot sau khi gửi phản hồi
                if should_reboot:
                    print("-> [SYSTEM] Đang Reboot giả lập (Ngắt kết nối sau 1s)...")
                    await asyncio.sleep(1) # delay(1000)
                    await websocket.close()
                    print("[SYSTEM] Đã ngắt kết nối (Reboot complete)")
                    return # Thoát khỏi vòng lặp nhận tin nhắn

            except json.JSONDecodeError:
                print("! Lỗi JSON Format")
            except Exception as e:
                print(f"! Lỗi xử lý: {e}")
                import traceback
                traceback.print_exc()

    except websockets.exceptions.ConnectionClosed:
        print("[Client] Ngắt kết nối")

async def main():
    print(f"=== ESP8266 MOCK SERVER v3 (Port {PORT}) ===")
    print(f"Host IP: {HOST}")
    print("Sẵn sàng...")
    async with websockets.serve(handler, HOST, PORT, ping_interval=None):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nĐã dừng server.")