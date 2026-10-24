# node-bridge 消费方 ProGuard 规则
# 只保留 JNI 入口（`NativeBridge` 的方法名与 `.so` 里的符号名逐字绑定）
-keepclasseswithmembernames class org.fellow99.nodebridge.NativeBridge {
    native <methods>;
}
-keep class org.fellow99.nodebridge.NodeRuntime { *; }
-keep class org.fellow99.nodebridge.NodeState { *; }
-keep class org.fellow99.nodebridge.NodeState$* { *; }
