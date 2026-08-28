-keepclassmembers class cn.com.omnimind.baselib.shizuku.OmnibotPrivilegedUserService {
    public <init>(...);
}

# McCloud API payloads are decoded reflectively by Gson. Keep their field names
# stable in minified app builds so login, captcha, and subsequent cloud calls
# use the server's JSON contract.
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.mccloud.** {
    <fields>;
}
