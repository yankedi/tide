package io.github.yankedi.tide

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    var session: TerminalSession? = null
        private set

    var terminalSessionClient: TerminalSessionClient? = null

    fun getOrCreateSession(
        shellPath: String,
        workingDirectory: String,
        args: Array<String>?,
        envs: Array<String>,
        client: TerminalSessionClient
    ): TerminalSession {
        this.terminalSessionClient = client
        val currentSession = session
        if (currentSession != null && currentSession.isRunning) {
            return currentSession
        }

        val newSession = TerminalSession(
            shellPath,
            workingDirectory,
            args,
            envs,
            2000,
            object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) {
                    terminalSessionClient?.onTextChanged(changedSession)
                }

                override fun onTitleChanged(changedSession: TerminalSession) {
                    terminalSessionClient?.onTitleChanged(changedSession)
                }

                override fun onSessionFinished(finishedSession: TerminalSession) {
                    terminalSessionClient?.onSessionFinished(finishedSession)
                }

                override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
                    terminalSessionClient?.onCopyTextToClipboard(session, text)
                }

                override fun onPasteTextFromClipboard(session: TerminalSession?) {
                    terminalSessionClient?.onPasteTextFromClipboard(session)
                }

                override fun onBell(session: TerminalSession) {
                    terminalSessionClient?.onBell(session)
                }

                override fun onColorsChanged(session: TerminalSession) {
                    terminalSessionClient?.onColorsChanged(session)
                }

                override fun onTerminalCursorStateChange(state: Boolean) {
                    terminalSessionClient?.onTerminalCursorStateChange(state)
                }

                override fun getTerminalCursorStyle(): Int {
                    return terminalSessionClient?.getTerminalCursorStyle() ?: 0
                }

                override fun logError(tag: String?, message: String?) {
                    terminalSessionClient?.logError(tag, message)
                }

                override fun logWarn(tag: String?, message: String?) {
                    terminalSessionClient?.logWarn(tag, message)
                }

                override fun logInfo(tag: String?, message: String?) {
                    terminalSessionClient?.logInfo(tag, message)
                }

                override fun logDebug(tag: String?, message: String?) {
                    terminalSessionClient?.logDebug(tag, message)
                }

                override fun logVerbose(tag: String?, message: String?) {
                    terminalSessionClient?.logVerbose(tag, message)
                }

                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                    terminalSessionClient?.logStackTraceWithMessage(tag, message, e)
                }

                override fun logStackTrace(tag: String?, e: Exception?) {
                    terminalSessionClient?.logStackTrace(tag, e)
                }
            }
        )
        session = newSession
        return newSession
    }

    fun sendCommand(command: String) {
        val data = (command + "\n").toByteArray()
        session?.write(data, 0, data.size)
    }

    override fun onCleared() {
        super.onCleared()
        session?.finishIfRunning()
    }
}
