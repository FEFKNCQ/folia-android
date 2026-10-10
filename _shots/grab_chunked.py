"""分块从设备取回截图，绕开单次传输被截断/被限制的问题。

背景：在这台机器上 `adb pull` 直接失败（退出码 1、0 字节），`adb exec-out` 单次也只能
拿到约 32KB，而手机上的 PNG 有 1MB 以上。所以改成用 dd 按固定块大小多次取，再本地拼接。
"""

import subprocess
import sys
import time
import struct

ADB = r"D:\Android\Sdk\platform-tools\adb.exe"
REMOTE = "/sdcard/Download/s.png"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"D:\Android\folia-sonnet\_shots\shot.png"
CHUNK = 30000


def adb(*args, timeout=60):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def wait_for_device(seconds=60):
    end = time.time() + seconds
    while time.time() < end:
        r = adb("get-state")
        if r.stdout.decode().strip() == "device":
            return True
        adb("start-server")
        time.sleep(1)
    return False


def remote_size():
    r = adb("exec-out", "stat", "-c", "%s", REMOTE)
    try:
        return int(r.stdout.decode().strip())
    except Exception:
        return 0


def main():
    if not wait_for_device():
        print("设备未连接")
        return 1

    # 重新截一张，避免用到陈旧文件
    adb("shell", "screencap", "-p", REMOTE)

    size = remote_size()
    print("远端文件大小:", size)
    if size <= 0:
        print("远端截图不存在")
        return 1

    buf = bytearray()
    offset = 0
    retries = 0
    while offset < size and retries < 8:
        r = adb("exec-out", "dd", f"if={REMOTE}", f"bs={CHUNK}",
                f"skip={offset // CHUNK}", "count=1")
        got = r.stdout
        if not got:
            retries += 1
            print(f"  块 {offset // CHUNK} 空，重试 {retries}")
            adb("start-server")
            time.sleep(1)
            continue
        buf.extend(got)
        offset += len(got)
        retries = 0

    print("累计取回:", len(buf), "/", size)

    if buf[:8] != b"\x89PNG\r\n\x1a\n":
        print("不是有效 PNG")
        return 1

    with open(OUT, "wb") as f:
        f.write(buf)
    w, h = struct.unpack(">II", bytes(buf[16:24]))
    print("尺寸:", w, "x", h)
    print("已保存:", OUT)
    return 0


sys.exit(main())
