# native 方法不能被混淆，否则 JNI 找不到实现
-keepclasseswithmembernames class com.xiaoye.qqbot.engine.LlmEngine {
    native <methods>;
}
-keep class com.xiaoye.qqbot.engine.TokenSink { *; }
