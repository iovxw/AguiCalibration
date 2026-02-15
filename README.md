# AguiCalibration

最小可用的重力计校准应用。

## 目标

- 不修改目标机 `system`
- 作为普通应用安装
- 通过 `adb root + app_process` 启动 root daemon，再由 app 通过本地 socket 请求校准
- 用标准 `SensorManager` 显示当前加速度 XYZ

## 关键实现

- `app/src/main/java/com/agui/calibration/vendor/IAgoldDaemon.java`
- `app/src/main/java/com/agui/calibration/vendor/IAgoldDaemonCallback.java`
- `app/src/main/java/com/agui/calibration/root/RootCalibrationCore.kt`
- `app/src/main/java/com/agui/calibration/root/RootCalibrationCli.kt`
- `app/src/main/java/com/agui/calibration/root/RootCalibrationDaemon.kt`
- `app/src/main/java/com/agui/calibration/root/RootCalibrationProxy.kt`
- `app/src/main/java/com/agui/calibration/MainActivity.kt`
- `start_root_daemon.sh`

root daemon 通过反射调用隐藏类 `android.os.ServiceManager`，查找 vendor AIDL 服务，再发起：

- `CommonGetResult(callback, 2)`

其中 `2` 为此前逆向确认的 GSensor 校准类型。

## 本地构建

```bash
cd /mnt/cache/Android/JellyMax/workdir/AguiCalibration
./gradlew assembleDebug
```

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./start_root_daemon.sh
adb shell am start -n com.agui.calibration/.MainActivity
```

## 启动/重启 root daemon

```bash
adb root
cd /mnt/cache/Android/JellyMax/workdir/AguiCalibration
./start_root_daemon.sh
```

在单独终端里运行这个脚本，并保持该终端打开；app 会通过本地 socket 连接这个 daemon。

## userdebug 设备调试建议

如果启动后 `Vendor 服务状态` 一直显示未连接，可检查：

```bash
adb shell service list | grep -i agold
adb logcat -s RootCalibrationDaemon RootCalibrationProxy AndroidRuntime
```

如遇 hidden API 反射限制，可在 userdebug 设备上临时放宽：

```bash
adb shell settings put global hidden_api_policy 1
adb shell settings put global hidden_api_policy_pre_p_apps 1
```

## 预期结果

- 页面能显示实时 XYZ
- 启动 root daemon 后，页面会显示 `root 可用，vendor daemon 可访问`
- 点击“开始校准”后，如果 root daemon 可访问 vendor daemon，返回 `0` 表示成功