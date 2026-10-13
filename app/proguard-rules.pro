# Keep rules are added per feature as they appear (Room, Hilt and Compose ship their own).

# No verbose/debug/info logs in release: they carry trip ids, file names and derived place names (review M16).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
