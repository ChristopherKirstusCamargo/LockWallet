# LOCK WALLET release rules.
# Defense in depth for spec §2.3: strip any android.util.Log call that might slip in through a dependency
# or future code. The primary control is the source scan (`verifyForbiddenApis`) — this is a backstop.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# ---- Phase I R8 review (2026-11-19) ----
# Reviewed consumer rules shipped by dependencies (they are applied automatically):
#  - CameraX 1.5.2: ships its own rules. ZXing core: no reflection on our path (QRCodeReader only).
#  - BouncyCastle: used through direct class references only (no JCA provider registration, no reflection).
# R8 reported no missing classes (no missing_rules.txt). The only reflection in our code is Class.forName on
# third-party hooking-framework class names (DeviceSecurityCapabilityManager), which must NOT be kept or renamed.
# Persisted enums are read back with valueOf(); keep their constants so journals/registries survive app updates
# even if a future R8 changes its enum optimisations.
-keepclassmembers enum com.lockwallet.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static final ** *;
}
