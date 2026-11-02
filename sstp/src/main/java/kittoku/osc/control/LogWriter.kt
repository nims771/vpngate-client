package kittoku.osc.control

import kittoku.osc.api.SstpEngine


// Изменено для VPN Gate Client: журнал отправляется в SstpEngine, а не в файл
internal class LogWriter {
    internal fun write(message: String) {
        SstpEngine.log(message)
    }

    internal suspend fun report(message: String) {
        write(message)
    }

    internal fun close() {}
}
