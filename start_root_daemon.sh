#!/usr/bin/env bash
set -euo pipefail

cd /mnt/cache/Android/JellyMax/workdir/AguiCalibration

current_uid="$(adb shell id -u | tr -d '\r')"
if [ "$current_uid" != "0" ]; then
  echo "adbd is not root. Run 'adb root' manually first, then rerun this script."
  exit 1
fi

pkg_path="$(adb shell pm path com.agui.calibration | tr -d '\r' | sed 's/^package://')"
if [ -z "$pkg_path" ]; then
  echo "Package path not found. Install the app first."
  exit 1
fi

echo "Starting root daemon in foreground. Keep this terminal open."
echo "APK: $pkg_path"
exec adb shell "CLASSPATH=$pkg_path /system/bin/app_process / com.agui.calibration.root.RootCalibrationDaemon"