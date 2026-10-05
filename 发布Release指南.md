# 发布 Release 完整指南

发布 release 和"能编译出 APK"是两件事。核心差别是**签名**。

---

## 为什么签名是硬门槛

Android 用签名判断"两个 APK 是不是同一个 App"：

| 情况 | 结果 |
|---|---|
| 签名相同、versionCode 递增 | ✅ 正常覆盖安装，数据保留 |
| 签名不同 | ❌ 系统拒绝安装，必须先卸载（**App 数据全丢**） |
| versionCode 没递增 | ❌ 拒绝安装 |

CI runner **每次都是全新虚拟机**，默认 debug keystore 每次重新生成 → **签名每次都不同**。
所以你之前每次装新版本可能都要卸载，就是这个原因。

**解决：生成一次 keystore，固定下来，以后每次都用它签。**

---

## 第一步：生成 keystore（只做一次，5 分钟）

在**你自己的电脑**上执行。用一个你能长期保存的目录，例如 `D:\AndroidKeys\`：

```cmd
mkdir D:\AndroidKeys

keytool -genkeypair -v ^
  -keystore D:\AndroidKeys\ycm-release.jks ^
  -alias ycm ^
  -keyalg RSA -keysize 2048 -validity 36500 ^
  -storepass 你的密码A ^
  -keypass 你的密码A
```

`keytool` 在 JDK 的 `bin` 目录下（你装了 Temurin JDK 21，应该已在 PATH）。

执行后会问一堆问题（姓名、组织、城市…）——**随便填，不影响**，这个信息不会显示给用户。
`-validity 36500` 表示 100 年，够用了。

> ### ⚠️ 三条铁律
>
> 1. **keystore 文件和密码必须长期备份**（网盘 + U 盘各一份）。
>    丢了就**永远无法给已安装的用户更新**，只能让他们卸载重装。
> 2. **绝对不能提交到 GitHub**。你的仓库是 Public，等于把钥匙公开——
>    任何人都能签出"你的"App，你无法阻止。
> 3. 密码别用太简单的，但也别自己都记不住。建议存进密码管理器。

本项目已在 [`.gitignore`](.gitignore) 里排除了 `*.jks` / `*.keystore` / `keystore.properties`。

---

## 第二步：把 keystore 转成 base64

GitHub Secret 只能存文本，所以要 base64 编码。

**PowerShell（推荐，Windows 自带）：**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\AndroidKeys\ycm-release.jks")) | Set-Clipboard
```

这会把 base64 直接**复制到剪贴板**（几 KB 的长字符串）。

**或者用 Git Bash：**

```bash
base64 -w 0 D:/AndroidKeys/ycm-release.jks | clip
```

**验证一下**（可选）：把剪贴板内容存成 `test.b64`，然后

```powershell
[IO.File]::WriteAllBytes("test.jks", [Convert]::FromBase64String((Get-Content test.b64 -Raw)))
# 能还原出同样大小的 jks 就说明没问题
```

---

## 第三步：添加 4 个 GitHub Secret

打开你的仓库：

**Settings → Secrets and variables → Actions → New repository secret**

依次添加这 4 个：

| Secret 名称 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上一步剪贴板里的 base64 长字符串 |
| `KEYSTORE_PASSWORD` | 你的"密码A"（storepass） |
| `KEY_ALIAS` | `ycm` |
| `KEY_PASSWORD` | 你的"密码A"（keypass，生成时设成一样了） |

> 名字必须**完全一致**，工作流按这些名字读取。
> 大小写敏感，注意不要多出空格或换行。

**没配置这 4 个 Secret 也能构建**——工作流会跳过 release，只出 debug 包，不会报错。

---

## 第四步：改版本号

在 [`gradle.properties`](gradle.properties) 里：

```properties
ycm.versionCode=5          # 必须比上一版大，否则无法覆盖安装
ycm.versionName=1.4.0
```

**每次发布都要递增 `versionCode`**，这是 Android 判断"是否为新版本"的依据。

---

## 第五步：发布

### 方式 A：打 tag 正式发布（推荐）

```cmd
cd /d "E:\DSH Desktop Workplace\玩具蓝牙协议分析\YcmRemote"
git add -A
git commit -m "v1.4.0 描述"
git push

git tag v1.4.0
git push origin v1.4.0
```

推送 tag 后会自动：

1. 构建 debug 包 + **签名后的 release 包**
2. 生成 `SHA256SUMS.txt` 校验文件
3. **自动创建 GitHub Release**，把 APK 附在 Release 里
4. Release 说明自动从提交记录生成

发布页地址：`https://github.com/Haiso169/BLE-ToyRemote/releases`

### 方式 B：只构建不发布

Actions → Build APK → Run workflow

产物在当次运行的 **Artifacts** 区域，保留 30 天。适合测试。

---

## 用户怎么安装

Release 页面下载 `app-release.apk` → 传到手机 → 点击安装。

因为带签名且 versionCode 递增，**以后的新版本可以直接覆盖安装，数据保留**。

---

## 验证签名是否正确

装好后用下面的命令对比新旧 APK 的签名指纹，应该**完全一致**：

```cmd
:: 需要 Android SDK 的 apksigner，或用下面这个更简单的方法
keytool -printcert -jarfile app-release.apk
```

输出里的 `SHA256:` 指纹，每次构建都应该是同一个值。

---

## 常见问题

| 问题 | 原因 / 处理 |
|---|---|
| `KEYSTORE_BASE64` 解码失败 | base64 里混入了换行或空格。用 `-w 0`（Git Bash）或 PowerShell 方法重新生成 |
| release 任务被跳过 | 4 个 Secret 没配齐。检查名字拼写，或 `KEYSTORE_BASE64` 为空 |
| 装不上，提示"应用未安装" | 签名与已装版本不同 → 卸载重装；或 versionCode 没递增 |
| 装上了但闪退 | release 开了 R8 混淆，可能是规则没覆盖到。把崩溃日志发我，补 [`proguard-rules.pro`](app/proguard-rules.pro) |
| 想换签名 | 只能让所有用户卸载重装。**没有别的办法**，这就是 Android 的设计 |
| 想上应用商店 | 商店要求 `.aab`，且签名策略不同。本项目面向自用，不涉及 |

---

## 关于 release 包的两个注意点

**1. 开了代码混淆和资源压缩**（`isMinifyEnabled` / `isShrinkResources`）。
好处是包更小、反编译更难；风险是**反射引用的东西可能被误删**。
我已经在 [`proguard-rules.pro`](app/proguard-rules.pro) 里保留了清单组件、协程、枚举等，
但**这个 release 包还没实跑过**——如果闪退，大概率是这里，把日志发我加规则。

**2. release 包和 debug 包可以共存吗？**
不能，包名相同（`com.ycm.remote`）。想同时装两个，得改 `applicationId`。
不过它们签名不同，**互相覆盖会失败**，需要先卸载。
