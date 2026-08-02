package com.scrc.android

object ScrcpyConstants {
    const val SERVER_VERSION = "4.1"
    const val SERVER_ASSET = "scrcpy-server-v4.1"
    const val SERVER_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
    const val DEVICE_NAME_FIELD_LENGTH = 64

    const val CODEC_H264 = 0x68323634 // "h264"
    const val CODEC_H265 = 0x68323635 // "h265"

    const val MSG_INJECT_KEYCODE = 0
    const val MSG_INJECT_TOUCH_EVENT = 2
    const val MSG_BACK_OR_SCREEN_ON = 4
    /** scrcpy TYPE_SET_DISPLAY_POWER：关闭物理屏背光，投屏仍继续 */
    const val MSG_SET_DISPLAY_POWER = 10

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4

    /** scrcpy SC_POINTER_ID_GENERIC_FINGER */
    const val POINTER_ID_FINGER = -2L
}
