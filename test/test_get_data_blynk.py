import asyncio
import aiohttp
import time

TOKEN = "dEPagFns7I1QqH1UkmxO2BCs5nDai8MM"   # đổi thành token thật của bạn
PIN = "v3"                   # v0, v1, v2...

URL = f"https://blynk.cloud/external/api/get?token={TOKEN}&{PIN}"


REQUESTS_PER_SECOND = 50
DURATION_SECONDS = 50  # test trong 5 giây

async def fetch(session, url):
    try:
        async with session.get(url, timeout=5) as response:
            value = await response.text()
            print(value)
    except Exception as e:
        print("Error:", e)

async def main():
    async with aiohttp.ClientSession() as session:
        start_time = time.time()
        count = 0
        while time.time() - start_time < DURATION_SECONDS:
            tasks = [fetch(session, URL) for _ in range(REQUESTS_PER_SECOND)]
            await asyncio.gather(*tasks)
            count += REQUESTS_PER_SECOND
            await asyncio.sleep(1)  # giữ tốc độ ~50 req/s
        print(f"Total requests sent: {count}")

if __name__ == "__main__":
    asyncio.run(main())