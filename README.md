# 打卡相机(DakaCamera)

一个给家人用的极简水印打卡相机(Android)。拍下照片时,自动把**时间、详细地址、日期**和**防伪码**合成到照片上,并可在 App 内凭防伪码查询拍摄时的真实时间与地点。

参考「今日水印相机」的版式,只保留最必要的功能,没有广告、没有账号、没有服务器。

## 功能

- 📷 **极简相机**:3:4 取景区 + 快门 + 前后摄切换 + 相册入口(底部左侧圆形缩略图,与镜头翻转对称)
- 🖼️ **布局**:上下白色操作区,水印位于取景区左下角,不会被快门按钮遮挡
- 👀 **实时水印预览**:拍照界面直接显示最终水印(时间每分钟刷新,与成片共用同一套绘制逻辑)
- ⚙️ **设置**:左上角齿轮进入设置,可填写高德 Web 服务 Key(仅保存在本机)
- 🕘 **左下角水印**:黄色「打卡」徽标 + 大号时间 + 详细地址(最多两行)+ 日期星期,底部小字「打卡相机已验证 | 时间地点真实」
- 🔐 **右下角防伪码**:20 位自包含防伪码(分组显示,如 `AB12-CD34-EF56-GH78-JK90`)
- ✅ **验真页**:输入防伪码,显示该照片拍摄时的真实时间、经纬度、地址
- 🎯 **定位校准**:一键列出附近 300 米内的地点,按距离由近到远排列,可手动选择;列表加载全程异步、带超时与取消,地点再多也不会卡界面;高德 Key 可直接在 App 内填写(公开 APK 不含任何 Key)
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

### 可选:高德地点数据

默认使用 OpenStreetMap(Overpass API,免注册;公共节点在部分网络下可能繁忙,失败时 App 会提示填写免费的高德 Key;国内乡镇的 POI 覆盖也可能较少)。

**方式一(推荐,适合开源使用者):在 App 内填写**

左上角齿轮(设置)→「高德 Web 服务 Key」→ 粘贴 Key → 保存。Key 只保存在本机 `SharedPreferences`,不会上传,也不会进入仓库或公开 APK。每台设备单独填写一次即可。

**方式二(私有构建):打包时内置**

```properties
# local.properties
daka.amapKey=你的高德「Web 服务」key
```

也支持环境变量 `DAKA_AMAP_KEY`。注意:内置的 Key 会进入 APK,公开发布前请勿配置。

Key 必须是高德开放平台中「服务平台 = Web 服务」类型的 Key(Android 平台 Key 不能用于本项目的 REST 调用)。

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
├── MainActivity.kt        # 主界面:预览、快门、权限、实时水印、定位校准
├── CameraController.kt    # CameraX 封装:前后摄、旋转、前摄镜像
├── WatermarkPainter.kt    # 水印绘制核心(实时预览与成片共用,保证所见即所得)
├── WatermarkOverlayView.kt# 相机界面实时水印层
├── WatermarkRenderer.kt   # 把水印合成到照片位图
├── AntiFakeCode.kt        # 防伪码编解码(纯 JVM,可单测)
├── LocationHelper.kt      # 定位 + 逆地理编码(系统 Geocoder / 可选高德)
├── PoiSearch.kt           # 附近 300 米地点搜索(OSM / 可选高德,异步可取消)
├── AppSettings.kt         # 本机设置(高德 Key 等,SharedPreferences)
├── GeoUtils.kt            # Haversine 距离计算
├── MediaStoreSaver.kt     # 保存到相册(MediaStore / 旧版兼容)
└── VerifyActivity.kt      # 验真页
```

## 权限与隐私

- 申请权限:**相机**、**定位**(粗略/精确,可选)、**联网**(仅用于地点搜索)、旧版系统(API ≤ 28)的存储写权限
- 联网仅用于以下两件事:
  - 「定位校准」搜索附近 300 米地点:请求只包含当前经纬度。默认发送给 OpenStreetMap Nominatim;配置高德 key 后发送给高德
  - 配置高德 key 时,自动地址解析也走高德逆地理编码
- 高德 Key 只保存在本机 `SharedPreferences`,仅作为请求参数发送给高德;仓库与公开 APK 均不包含 Key
- 照片、防伪码不会被上传;没有账号、统计和广告
- 地址解析优先使用系统 `Geocoder`(国内无 GMS 机型常常不可用,此时自动降级显示经纬度;配置高德 key 后改用高德,地址更准)
- 所有照片只保存在本机相册

## 防伪码说明(请如实理解)

防伪码把「时间戳 + 经纬度」加密后编码成 20 个字符,由 App 离线校验和解码:

- ✅ 能验证:编码合法、未被篡改,并还原出拍摄时记录的时间与地点
- ⚠️ 不是密码学意义上的不可伪造:密钥内置在 APK 中,反编译后可提取并伪造
- ⚠️ 时间取自拍摄设备的系统时钟,手动修改系统时间可让水印与码同时变成假时间
- ⚠️ 验真页只能证明「码与其中记录的时间和地点自洽」,不能证明照片未被后期处理

它的定位是**威慑级防伪**:挡住普通的改图、改时间、转发冒用,适合家庭日常打卡场景,不适用于法律取证等高可信要求场景。

## 更新日志

- **v1.3**:界面改为 3:4 取景区 + 上下白色操作区(水印与按钮不再重叠);顶部居中「注意安全」提示;定位校准移至右侧中部;左上角新增设置入口(高德 Key 在这里填写,搜索半径可选 100~500 米);底部左侧新增相册入口(最新照片圆形缩略图);高德 Key 不可用时自动回退 OSM;定位同时请求网络与 GPS,更稳
- **v1.2**:高德 Key 改为 App 内填写(公开 APK 与仓库不含 Key);配置高德后自动地址解析也走高德
- **v1.1**:实时水印预览;定位校准(附近 300 米地点,OSM 默认 / 高德可选);新增联网权限
- **v1.0**:首个版本

## 已知限制

- 地址精度与附近地点覆盖取决于数据源:OSM(Overpass)公共节点可能繁忙,国内乡镇覆盖也较少;接入免费的高德 Key 后明显更稳、更全
- 取景区与成片均为 3:4,水印位置一致;极少数机型若成片比例不同,可能有轻微裁切差异
- 界面锁定竖屏(保证水印始终正向)
- 前摄照片会做水平镜像(与预览画面一致),水印文字不受影响

## 贡献

欢迎提 Issue / PR。提交前请确保 `./gradlew :app:assembleRelease` 与 `./gradlew :app:testDebugUnitTest` 均通过。

## License

[MIT](LICENSE)
