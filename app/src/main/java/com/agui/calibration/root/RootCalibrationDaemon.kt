package com.agui.calibration.root

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object RootCalibrationDaemon {
    const val SOCKET_NAME = "agui_calibrationd"
    private const val TAG = "RootCalibrationDaemon"

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "Starting daemon on abstract socket $SOCKET_NAME")
        val server = LocalServerSocket(SOCKET_NAME)
        while (true) {
            val client = server.accept()
            Thread {
                handleClient(client)
            }.start()
        }
    }

    private fun handleClient(client: LocalSocket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = OutputStreamWriter(socket.outputStream)
            val command = reader.readLine()?.trim().orEmpty()
            if (command.isEmpty()) {
                writer.write("ERROR empty_command\n")
                writer.flush()
                return
            }
            Log.i(TAG, "Handling command=$command")
            val (output, _) = RootCalibrationCore.execute(command)
            writer.write(output)
            writer.write("\n")
            writer.flush()
        }
    }
}