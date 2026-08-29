-keepclassmembers class cn.com.omnimind.baselib.shizuku.OmnibotPrivilegedUserService {
    public <init>(...);
}

# McCloud API payloads are created reflectively by Gson through generic
# TypeTokens. Preserve the classes as well as their fields in full-mode R8;
# keeping field names alone still lets R8 merge or remove reflective DTOs.
-keep class cn.com.omnimind.baselib.mccloud.** { *; }
