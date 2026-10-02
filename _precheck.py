"""编译前自检：校验工程结构与 YAML 里的假设是否一致。
本机无 Android SDK，无法真正编译，但可以静态查出绝大多数会导致 CI 失败的问题。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
# 本脚本就放在工程根目录（YcmRemote/）内
proj = ROOT
ok = True


def check(cond, msg):
    global ok
    print(("  OK   " if cond else "  FAIL ") + msg)
    if not cond:
        ok = False


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


print("=== 1. 关键文件是否存在 ===")
for rel in [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/ycm/remote/MainActivity.kt",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/themes.xml",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    ".github/workflows/build-apk.yml",
]:
    check(os.path.exists(os.path.join(proj, rel)), rel)

print("\n=== 2. settings.gradle.kts 的 rootProject.name 与 CI 路径 ===")
s = read(os.path.join(proj, "settings.gradle.kts"))
check('include(":app")' in s, 'include(":app")')
m = re.search(r'rootProject\.name\s*=\s*"([^"]+)"', s)
check(m is not None, "rootProject.name 已设置")
if m:
    print(f"        rootProject.name = {m.group(1)}")

print("\n=== 3. app/build.gradle.kts 关键配置 ===")
b = read(os.path.join(proj, "app/build.gradle.kts"))
check('id("com.android.application")' in b, "应用了 com.android.application")
check('id("org.jetbrains.kotlin.android")' in b, "应用了 kotlin.android")
check("compose = true" in b, "已开启 compose")
compile_sdk = re.search(r"compileSdk\s*=\s*(\d+)", b)
check(compile_sdk is not None, "声明了 compileSdk")
if compile_sdk:
    print(f"        compileSdk = {compile_sdk.group(1)}  (CI 装 platforms;android-34)")
ns = re.search(r'namespace\s*=\s*"([^"]+)"', b)
check(ns is not None, "声明了 namespace")
if ns:
    print(f"        namespace = {ns.group(1)}")
check("lifecycle-runtime-compose" in b, "含 lifecycle-runtime-compose（collectAsStateWithLifecycle 依赖）")

print("\n=== 4. namespace 与 MainActivity 包名是否一致 ===")
if ns:
    act = os.path.join(proj, "app/src/main/java", ns.group(1).replace(".", "/"), "MainActivity.kt")
    check(os.path.exists(act), f"MainActivity.kt 位于 namespace 对应目录 ({ns.group(1)})")
    if os.path.exists(act):
        check(f"package {ns.group(1)}" in read(act), "MainActivity 的 package 声明正确")

print("\n=== 5. AndroidManifest 引用的资源是否都存在 ===")
mani = read(os.path.join(proj, "app/src/main/AndroidManifest.xml"))
check('android:name=".MainActivity"' in mani, "注册了 .MainActivity")
check("BLUETOOTH_SCAN" in mani and "BLUETOOTH_CONNECT" in mani, "含 Android 12+ 蓝牙权限")
check("ACCESS_FINE_LOCATION" in mani, "含 Android 11- 定位权限")
res = os.path.join(proj, "app/src/main/res")
check(os.path.exists(os.path.join(res, "mipmap-anydpi-v26/ic_launcher.xml")), "@mipmap/ic_launcher 存在(v26)")
check(os.path.exists(os.path.join(res, "mipmap-hdpi/ic_launcher.xml")), "@mipmap/ic_launcher 存在(低版本回退)")
check(os.path.exists(os.path.join(res, "drawable/ic_launcher_background.xml")), "ic_launcher_background 存在")
check(os.path.exists(os.path.join(res, "drawable/ic_launcher_foreground.xml")), "ic_launcher_foreground 存在")
check('name="Theme.YcmRemote"' in read(os.path.join(res, "values/themes.xml")), "Theme.YcmRemote 已定义")

print("\n=== 6. Kotlin 源文件清单 ===")
kt = []
for dirpath, _, files in os.walk(os.path.join(proj, "app/src/main/java")):
    for f in files:
        if f.endswith(".kt"):
            kt.append(os.path.relpath(os.path.join(dirpath, f), proj))
for f in sorted(kt):
    print("        " + f)
check(len(kt) >= 9, f"Kotlin 文件数量 {len(kt)} >= 9")

print("\n=== 7. 所有 .kt 的 package 声明与目录是否匹配 ===")
SRC_ROOT = os.path.join(proj, "app", "src", "main", "java")
for rel in kt:
    src = read(os.path.join(proj, rel))
    pm = re.search(r"^package\s+([\w.]+)", src, re.M)
    if not pm:
        check(False, f"{rel} 缺少 package 声明")
        continue
    pkg_dir = os.path.dirname(os.path.join(proj, rel))
    rel_dir = os.path.relpath(pkg_dir, SRC_ROOT).replace("\\", ".").replace("/", ".")
    if pm.group(1) != rel_dir:
        check(False, f"{rel}: package {pm.group(1)} != 目录 {rel_dir}")
    else:
        check(True, f"{rel}: package {pm.group(1)}")

print("\n=== 8. CI 工作流要点 ===")
wf = read(os.path.join(proj, ".github/workflows/build-apk.yml"))
check("working-directory: YcmRemote" in wf, "CI 中工作目录为 YcmRemote（与仓库根包含该目录一致）")
check("assembleDebug" in wf, "执行 assembleDebug")
check("upload-artifact@v4" in wf, "使用 upload-artifact@v4")
check("java-version: '17'" in wf, "使用 JDK 17")
check("gradle-version: '8.7'" in wf, "使用 Gradle 8.7")
check("platforms;android-34" in wf, "安装 platforms;android-34")

print("\n=== 9. 交叉检查：AGP 与 Gradle 版本兼容 ===")
root_b = read(os.path.join(proj, "build.gradle.kts"))
agp = re.search(r'com\.android\.application"\)\s*version\s*"([\d.]+)"', root_b)
kotlin_v = re.search(r'kotlin\.android"\)\s*version\s*"([\d.]+)"', root_b)
if agp:
    print(f"        AGP = {agp.group(1)}  (要求 Gradle >= 8.7)")
    check(tuple(int(x) for x in agp.group(1).split(".")) >= (8, 5), "AGP >= 8.5")
    check("gradle-version: '8.7'" in wf, "Gradle 8.7 满足 AGP 8.5.x 的最低要求")
if kotlin_v:
    print(f"        Kotlin = {kotlin_v.group(1)}")

print("\n" + "=" * 46)
print("自检通过，可以推送到 GitHub 编译" if ok else "自检发现失败项，需先修复")
sys.exit(0 if ok else 1)
