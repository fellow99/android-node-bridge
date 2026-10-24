package org.fellow99.nodebridge

import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主能力桥的**注册表**（模块 003-host-bridge）。
 *
 * 纪律：未注册的名字**结构化报错**，绝不返回空串、绝不崩溃
 * （HarmonyOS 版的反面教材：未注册能力把整个应用打崩）。
 */
class NodeHostBridge internal constructor() {

    private val handlers = ConcurrentHashMap<String, (String) -> String>()

    /** 注册一个宿主能力；handler 在**主线程**被调用，入参/出参都是 JSON 字符串。 */
    fun register(name: String, handler: (payloadJson: String) -> String) {
        require(name.isNotBlank()) { "host bridge: name 不能为空" }
        handlers[name] = handler
    }

    fun unregister(name: String) {
        handlers.remove(name)
    }

    fun names(): List<String> = handlers.keys.sorted()

    /** 供自检载荷/诊断：把未注册也变成可判定的 JSON，而不是异常穿透到 Node */
    internal fun dispatch(name: String, argsJson: String): String {
        val handler = handlers[name]
            ?: return """{"ok":false,"error":"unregistered","name":${quote(name)}}"""
        return try {
            handler(argsJson)
        } catch (t: Throwable) {
            """{"ok":false,"error":"handler-threw","name":${quote(name)},"message":${quote(t.message ?: t.javaClass.name)}}"""
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
