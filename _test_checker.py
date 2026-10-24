"""故障注入测试：确认 _check_imports.py 能抓到「跨包类名漏 import」。
流程：备份 -> 删掉某文件的一个 import -> 跑检查（期望失败）-> 还原 -> 再跑（期望通过）
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(ROOT, "app/src/main/java/com/ycm/remote/ui/RhythmScreen.kt")
IMPORT_LINE = "import com.ycm.remote.device.YcmBl001Profile\n"
CHECKER = os.path.join(ROOT, "_check_imports.py")


def run_checker():
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    r = subprocess.run(
        [sys.executable, CHECKER],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env,
    )
    return r.returncode, r.stdout


def main():
    if not os.path.exists(TARGET):
        print(f"目标文件不存在: {TARGET}")
        return 2

    original = open(TARGET, encoding="utf-8").read()
    if IMPORT_LINE not in original:
        print("目标 import 不在文件里，无法注入")
        return 2

    backup = os.path.join(tempfile.gettempdir(), "RhythmScreen.kt.injectbak")
    shutil.copy2(TARGET, backup)

    try:
        # 注入：删掉 import
        broken = original.replace(IMPORT_LINE, "", 1)
        with open(TARGET, "w", encoding="utf-8", newline="\n") as f:
            f.write(broken)

        print("=== 注入故障后 ===")
        code, out = run_checker()
        print(out.strip())
        print(f"exit={code}")
        caught = code != 0 and "YcmBl001Profile" in out

        # 还原
        with open(TARGET, "w", encoding="utf-8", newline="\n") as f:
            f.write(original)

        print("\n=== 还原后 ===")
        code2, out2 = run_checker()
        print(f"exit={code2}")
        restored = code2 == 0

        print("\n" + "=" * 50)
        if caught:
            print("✓ 检查器成功抓到漏 import")
        else:
            print("✗ 检查器没抓到（存在 bug）")
        if restored:
            print("✓ 还原成功，检查重新通过")
        else:
            print("✗ 还原失败")
        return 0 if (caught and restored) else 1
    finally:
        if os.path.exists(backup):
            shutil.copy2(backup, TARGET)
            os.remove(backup)


if __name__ == "__main__":
    sys.exit(main())
