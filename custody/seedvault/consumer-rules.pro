# Seed Vault SDK: Parcelable CREATOR fields are read reflectively by the framework.
-keepclassmembers class com.solanamobile.seedvault.** implements android.os.Parcelable { public static final ** CREATOR; }
