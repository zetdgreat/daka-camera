# 打卡相机(DakaCamera)

一个给家人用的极简水印打卡相机(Android)。拍下照片时,自动把**时间、详细地址、日期**和**防伪码**合成到照片上,并可在 App 内凭防伪码查询拍摄时的真实时间与地点。

参考「今日水印相机」的版式,只保留最必要的功能,没有广告、没有账号、没有服务器。

## 功能

- 📷 **极简相机**:全屏预览 + 快门 + 前后摄切换
- 🕘 **左下角水印**:黄色「打卡」徽标 + 大号时间 + 详细地址(最多两行)+ 日期星期,底部小字「打卡相机已验证 | 时间地点真实」
- 🔐 **右下角防伪码**:20 位自包含防伪码(分组显示,如 `AB12-CD34-EF56-GH78-JK90`)
- ✅ **验真页**:输入防伪码,显示该照片拍摄时的真实时间、经纬度、地址
- 💾 **自动保存**:照片存入系统相册「打卡相机」文件夹,JPEG 质量 95
- 📍 **无网也能拍**:定位/地址解析失败时自动降级显示经纬度;没有定位权限也能正常拍照(水印显示「无定位信息」)

## 下载安装

前往 [Releases](../../releases) 下载最新 APK,在手机上允许「安装未知应用」后安装。

- 最低支持 Android 7.0(API 24)
- 首次打开需授权相机;定位可选

## 构建

### 环境

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 36 + build-tools 36.0.0 |
| Gradle | 由仓库内 wrapper 提供(8.14.6),无需预装 |
| AGP / Kotlin | 8.13.2 / 2.3.21 |
| CameraX | 1.6.2 |

> 注意:`androidx.core:core-ktx` 锁定 **1.18.0**。1.19.x 要求 compileSdk 37 + AGP 9.1,会导致构建失败。不要随手升级。

### 命令

```bash
# 1. 在 local.properties 中写上自己的 SDK 路径
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 构建
./gradlew :app:assembleRelease
# 产物:app/build/outputs/apk/release/app-release.apk

# 单元测试(防伪码编解码)
./gradlew :app:testDebugUnitTest
```

### 签名(可选)

仓库**不包含**签名密钥。未配置签名时,`assembleRelease` 产出未签名 APK,可直接用于本地调试安装。

如需签名,在 `local.properties` 中添加(该文件已在 `.gitignore` 中排除):

```properties
daka.storeFile=keystore/your-release.jks
daka.storePassword=******
daka.keyAlias=your-alias
daka.keyPassword=******
```

也支持环境变量:`DAKA_STORE_FILE` / `DAKA_STORE_PASSWORD` / `DAKA_KEY_ALIAS` / `DAKA_KEY_PASSWORD`。

生成密钥示例:

```bash
keytool -genkeypair -v -keystore keystore/your-release.jks \
  -alias your-alias -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass ****** -keypass ******
```

## 项目结构

```
app/src/main/java/com/family/dakacamera/
├── MainActivity.kt        # 主界面:预览、快门、权限编排、拍照流程
├── CameraController.kt    # CameraX 封装:前后摄、旋转、前摄镜像
├── WatermarkRenderer.kt   # 水印绘制(按图片宽度等比缩放)
├── AntiFakeCode.kt        # 防伪码编解码(纯 JVM,可单测)
├── LocationHelper.kt      # 定位 + 系统 Geocoder 逆地理编码
├── MediaStoreSaver.kt     # 保存到相册(MediaStore / 旧版兼容)
└── VerifyActivity.kt      # 验真页
```

## 权限与隐私

- 申请权限:**相机**、**定位**(粗略/精确,可选)、旧版系统(API ≤ 28)的存储写权限
- **没有申请 `INTERNET` 权限** —— App 自身不会联网,照片、位置、防伪码都不会被上传
- 地址解析调用 Android 系统 `Geocoder` 服务(由手机系统实现,是否联网取决于 ROM;国内无 GMS 机型常常不可用,此时自动降级显示经纬度)
- 所有数据只保存在本机相册

## 防伪码说明(请如实理解)

防伪码把「时间戳 + 经纬度」加密后编码成 20 个字符,由 App 离线校验和解码:

- ✅ 能验证:编码合法、未被篡改,并还原出拍摄时记录的时间与地点
- ⚠️ 不是密码学意义上的不可伪造:密钥内置在 APK 中,反编译后可提取并伪造
- ⚠️ 时间取自拍摄设备的系统时钟,手动修改系统时间可让水印与码同时变成假时间
- ⚠️ 验真页只能证明「码与其中记录的时间和地点自洽」,不能证明照片未被后期处理

它的定位是**威慑级防伪**:挡住普通的改图、改时间、转发冒用,适合家庭日常打卡场景,不适用于法律取证等高可信要求场景。

## 已知限制

- 地址精度依赖手机系统自带的逆地理编码服务;若需要参考图那种村镇级地址,可自行接入高德/百度等地图服务的 API Key
- 界面锁定竖屏(保证水印始终正向)
- 前摄照片会做水平镜像(与预览画面一致),水印文字不受影响

## 贡献

欢迎提 Issue / PR。提交前请确保 `./gradlew :app:assembleRelease` 与 `./gradlew :app:testDebugUnitTest` 均通过。

## License

[MIT](LICENSE)
