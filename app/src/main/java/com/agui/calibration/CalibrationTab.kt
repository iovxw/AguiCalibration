package com.agui.calibration

internal enum class CalibrationTab(val label: String, val iconText: String) {
    Authorization("授权", "A"),
    GSensor("重力计", "G"),
    Gyroscope("陀螺仪", "Y"),
    Alsps("ALS/PS", "P"),
    Logs("日志", "L")
}

internal val ALSPS_ORIGINAL_TIPS = listOf(
    "此操作分三步完成。\n步骤一：移除距离传感器正前方物体，点击开始。",
    "步骤二：请将标准遮挡物放置在距离传感器前,\n尽最大可能靠近距离传感器，\n点击下一步。",
    "步骤二：请将遮挡物放置在距离传感器\n正前方，点击下一步。",
    "步骤三：请将遮挡物渐渐靠近距离传感器\n或远离距离传感器正前方，验证校准是否成功。",
    "测试：点击下一步,\n并将遮挡物渐渐靠近距离传感器\n或远离距离传感器正前方，验证校准是否成功。"
)