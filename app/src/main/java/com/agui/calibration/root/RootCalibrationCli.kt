package com.agui.calibration.root

object RootCalibrationCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("ERROR missing_command")
            System.exit(2)
            return
        }

        try {
            val (output, exitCode) = RootCalibrationCore.execute(args.joinToString(" "))
            println(output)
            System.exit(exitCode)
        } catch (t: Throwable) {
            t.printStackTrace(System.out)
            println("ERROR exception:${t.javaClass.name}")
            System.exit(1)
        }
    }
}