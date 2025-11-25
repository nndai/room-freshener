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

# 1. Danh sách Task (Struct C++: id, hour, minute, weekday, enabled, padding, duration)
STRUCT_FORMAT = '<BBBB?xxxI' 
tasks = [] 
next_task_id = 1

# 2. Dữ liệu thống kê (Home Data)
home_data = {
    "temperature": 28.5,       # Giữ nguyên lỗi chính tả 'tempature' theo code C++ của bạn
    "humidity": 70.2,
    "totalSprayCount": 12,
    "totalSprayDuration": 60000, # ms
    
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
tasks.append(create_task(8, 0, 127, 5000, True))   # 8:00 sáng hàng ngày
tasks.append(create_task(17, 30, 62, 3000, True))  # 17:30 (T2-T6)
tasks.append(create_task(20, 15, 1, 10000, False)) # 20:15 (CN) - Đang tắt

def get_next_spray_info():
    """
    Tính toán lịch phun kế tiếp dựa trên thời gian thực tế của máy tính
    Logic đơn giản hóa: Tìm task enabled có (giờ, phút) > hiện tại.
    """
    now = datetime.now()
    cur_h = now.hour
    cur_m = now.minute
    
    # Tìm task gần nhất trong ngày hôm nay
    candidate = None
    min_diff = 999999

    # Sắp xếp task theo giờ phút để dễ tìm
    sorted_tasks = sorted(tasks, key=lambda x: x['hour'] * 60 + x['minute'])

    found_today = False
    
    # 1. Tìm trong ngày hôm nay (sau giờ hiện tại)
    for t in sorted_tasks:
        if not t['enabled']: continue
        
        t_time = t['hour'] * 60 + t['minute']
        c_time = cur_h * 60 + cur_m
        
        if t_time > c_time:
            candidate = t
            found_today = True
            break
    
    # 2. Nếu hôm nay hết rồi, lấy task sớm nhất của "ngày mai" (task đầu tiên trong list sorted)
    if not found_today:
        for t in sorted_tasks:
            if t['enabled']:
                candidate = t
                break
    
    if candidate:
        return {
            "hour": candidate['hour'],
            "minute": candidate['minute'],
            "duration": candidate['duration']
        }
    else:
        # Không có task nào active
        return {"hour": 0, "minute": 0, "duration": 0}

# --- XỬ LÝ WEBSOCKET ---

async def handler(websocket):
    print(f"[Client] Đã kết nối từ {websocket.remote_address}")
    
    try:
        async for message in websocket:
            print(f"\n[Nhận] {message}")
            try:
                doc = json.loads(message)
                command = doc.get("command", "")
                response = None

                # ==========================================
                # XỬ LÝ LỆNH
                # ==========================================

                if command == "sprayNow":
                    duration = doc.get("duration", 0)
                    print(f"-> [THỰC THI] Phun sương ngay lập tức: {duration}ms")
                    
                    # Cập nhật dữ liệu giả lập để App thấy thay đổi khi gọi getHomeData
                    now = datetime.now()
                    home_data["last_hour"] = now.hour
                    home_data["last_minute"] = now.minute
                    home_data["last_duration"] = duration
                    home_data["last_reason"] = 1 # Manual
                    home_data["totalSprayCount"] += 1
                    home_data["totalSprayDuration"] += duration

                elif command == "getHomeData":
                    # Giả lập cảm biến dao động nhẹ
                    temp_jitter = random.uniform(-0.5, 0.5)
                    hum_jitter = random.uniform(-1.0, 1.0)
                    
                    # Tính toán Next Spray
                    next_spray = get_next_spray_info()

                    response = {
                        "command": "getHomeDataResponse",
                        "temperature": round(home_data["temperature"] + temp_jitter, 1), # Lưu ý: giữ nguyên lỗi chính tả 'tempature'
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

                elif command == "getTime":
                    current_ts = int(time.time())
                    response = {"command": "getTimeResponse", "timestamp": current_ts}

                elif command == "setTime":
                    new_ts = doc.get("timestamp", 0)
                    print(f"-> Sync Time: {new_ts}")
                    response = {
                        "command": "setTimeResponse",
                        "status": True,
                        "message": f"RTC time updated: {new_ts}"
                    }

                elif command == "addTaskSpray":
                    h = doc.get("hour", 0); m = doc.get("minute", 0)
                    wd = doc.get("weekday", 0); dur = doc.get("duration", 0)
                    en = doc.get("enabled", False)
                    
                    new_task = create_task(h, m, wd, dur, en)
                    tasks.append(new_task)
                    print(f"-> Thêm Task ID {new_task['id']}")
                    
                    response = {
                        "command": "addTaskSprayResponse",
                        "status": True,
                        "taskId": new_task['id'],
                        "message": "success."
                    }

                elif command == "removeTaskSpray":
                    tid = doc.get("taskId", 0)
                    initial_len = len(tasks)
                    tasks[:] = [t for t in tasks if t['id'] != tid]
                    
                    if len(tasks) < initial_len:
                        print(f"-> Xóa Task ID {tid}")
                        response = {"command": "removeTaskSprayResponse", "status": True, "message": "success."}
                    else:
                        response = {"command": "removeTaskSprayResponse", "status": False, "message": "Task not found."}

                elif command == "setTaskEnabled":
                    tid = doc.get("task_id", 0)
                    enabled = doc.get("enabled", False)
                    found = False
                    for t in tasks:
                        if t['id'] == tid:
                            t['enabled'] = enabled
                            found = True; break
                    
                    response = {
                        "command": "setTaskEnabledResponse", 
                        "status": found, 
                        "message": "success." if found else "Task not found."
                    }

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
                    
                    response = {
                        "command": "editTaskSprayResponse", 
                        "status": found, 
                        "message": "success." if found else "Task not found."
                    }

                elif command == "getAllTaskSpray":
                    # Pack struct binary giống hệt C++
                    binary_data = bytearray()
                    for t in tasks:
                        packed = struct.pack(STRUCT_FORMAT, 
                                             t['id'], t['hour'], t['minute'], t['weekday'], 
                                             t['enabled'], t['duration'])
                        binary_data.extend(packed)
                    
                    b64_str = ""
                    if len(binary_data) > 0:
                        b64_str = base64.b64encode(binary_data).decode('utf-8')

                    response = {
                        "command": "getAllTasksSprayResponse",
                        "countask": len(tasks),
                        "sizeTask": struct.calcsize(STRUCT_FORMAT),
                        "dataTask": b64_str
                    }
                    print(f"-> Gửi {len(tasks)} tasks")

                else:
                    print(f"-> Unknown: {command}")
                    response = {"command": "unknownCommandResponse", "status": False, "message": "Unknown command."}

                # Gửi phản hồi
                if response:
                    await websocket.send(json.dumps(response))

            except json.JSONDecodeError:
                print("! Lỗi JSON Format")
            except Exception as e:
                print(f"! Lỗi xử lý: {e}")

    except websockets.exceptions.ConnectionClosed:
        print("[Client] Ngắt kết nối")

async def main():
    print(f"=== ESP8266 MOCK SERVER v2 (Port {PORT}) ===")
    print(f"Host IP: {HOST}")
    print("Đang chờ App kết nối...")
    
    async with websockets.serve(handler, HOST, PORT, ping_interval=None):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nĐã dừng server.")