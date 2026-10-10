"""从通过 adb 连接的安卓设备抓一张截图。

不用 shell 重定向（Windows 下 `adb exec-out > file` 会做换行转换，把 PNG 弄坏），
而是直接用 subprocess 拿二进制流写盘。
"""

import subprocess
import sys
import struct

ADB = r"D:\Android\Sdk\platform-tools\adb.exe"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"D:\Android\folia-sonnet\_shots\shot.png"

subprocess.run([ADB, "start-server"], capture_output=True)

proc = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True)
data = proc.stdout

if proc.stderr:
    print("adb stderr:", proc.stderr.decode("utf-8", "ignore").strip()[:300])

ok = data[:8] == b"\x89PNG\r\n\x1a\n"
print("bytes:", len(data), "| PNG 有效:", ok)

if ok:
    with open(OUT, "wb") as f:
        f.write(data)
    w, h = struct.unpack(">II", data[16:24])
    print("尺寸:", w, "x", h)
    print("已保存:", OUT)
    sys.exit(0)

sys.exit(1)
