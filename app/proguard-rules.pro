# ═══════════════════════════════════════════════════════════════════════════
# SimpleInventory — ProGuard / R8 Rules
# ═══════════════════════════════════════════════════════════════════════════
# These rules prevent R8 from removing or renaming classes that are accessed
# via reflection, serialization, or external APIs at runtime.
# ═══════════════════════════════════════════════════════════════════════════

# Preserve stack traces for debugging release crashes
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Flatten renamed classes into a single top-level package. Only affects
# classes R8 already decided to obfuscate (kept classes are unaffected),
# so this is behavior-neutral — pure win for Play Console's "Repackage
# classes" signal and a smaller string pool for package name segments.
-repackageclasses

# Strip verbose log calls from release builds.
# Log.w and Log.e are kept — they guard real error paths.
# Log.d / Log.i / Log.v are development-only and log business data (product
# names, invoice numbers, UIDs) that must not appear in release logcat.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
}

# ── App resources referenced in layouts ──────────────────────────────────
# R8 resource shrinking removes mipmap/ic_launcher if only referenced in
# XML layouts (not in code). Keep all app icon variants explicitly.
-keep class com.simhadri.winentry.R$mipmap { *; }
-keep class com.simhadri.winentry.R$drawable { *; }
-keep class com.simhadri.winentry.R$layout { *; }
-keep class com.simhadri.winentry.R$id { *; }
-keep class com.simhadri.winentry.R$navigation { *; }
# Room's generated Dao_Impl/RoomDatabase_Impl classes access entity fields
# via direct compiled calls, not reflection — R8 renames both sides
# consistently, so entities/repositories need no blanket app-wide keep here.
# Repositories are plain Kotlin wrappers with no reflection or serialization.

# ── Room ─────────────────────────────────────────────────────────────────
# Room finds AppDatabase_Impl by name and calls its no-arg constructor; its
# own consumer rules cover that. Entities and DAOs are reached only through
# generated code, so they need no keep (the blanket keeps were removed in vc22).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ── Firebase Auth ─────────────────────────────────────────────────────────
# firebase-auth's own bundled proguard.txt already keeps what its internal
# reflection actually touches (com.google.android.gms.internal.** response
# classes) — our app only calls the public Auth API directly (currentUser,
# signInWithCredential, etc.), which R8 keeps reachable on its own. No
# blanket package keep needed; GoogleAuthProvider name is kept as a
# narrow safety net since app code may look it up by provider id string.
-keepnames class com.google.firebase.auth.GoogleAuthProvider { *; }

# ── Firebase Firestore ────────────────────────────────────────────────────
# firebase-firestore's own bundled proguard.txt has no -keep rules at all —
# it relies on the app to protect only its own POJO model classes used with
# toObject()/DocumentId/ServerTimestamp. UserProfile is the only such model.
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.ServerTimestamp <fields>;
}
-keep class com.simhadri.winentry.data.UserProfile { *; }

# ── Google Sign-In / GMS ─────────────────────────────────────────────────
# play-services-basement/base ship their own rules for SafeParcelable and
# the classes they load by name; the blanket auth/common/tasks keeps and
# -keepnames gms.** were removed in vc22.
-dontwarn com.google.android.gms.**

# ── Google Sign-In internal classes (Parcelable — R8 must not remove) ────
# These classes are serialized/deserialized via Android Parcel between
# processes during sign-in. R8 removes them because they appear unreferenced
# in code — but the system needs them at runtime via reflection.
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.auth.api.signin.internal.** { *; }
# The two -keepclassmembers blocks that used to follow (repeating <init>/
# <fields> for the same two packages) were fully redundant — the -keep
# rules above already retain every member via `{ *; }`. Verified play-
# services-auth-20.7.0.aar ships zero bundled consumer proguard rules
# (only META-INF/MANIFEST.MF), so the internal.** keep above is the only
# protection for its ~25 Parcelable internal classes — do not narrow it
# without testing sign-in end to end.

# Apache POI (and its xmlbeans, commons-compress and log4j keeps) was removed in
# vc22; Excel files go through utils/xlsx, which uses no reflection.

# ── WorkManager ───────────────────────────────────────────────────────────
# WorkManager persists the worker's fully-qualified class name as a string
# and reconstructs it via reflection later — the class name must survive,
# but (narrowed from a blanket `{ *; }`) its internal methods/fields don't
# need protecting beyond the constructor WorkManager actually calls.
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
# Keep SyncWorker specifically
-keep class com.simhadri.winentry.sync.SyncWorker { *; }

# ── Navigation Component ──────────────────────────────────────────────────
# Navigation instantiates destination fragments reflectively by class name
# from nav_graph.xml — only the name + no-arg constructor need protecting
# (narrowed from a blanket `{ *; }` that kept every fragment's full body).
-keep class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# ── Kotlin ────────────────────────────────────────────────────────────────
# No kotlin-reflect usage in this app, so Metadata and coroutine names need
# no keep; kotlinx-coroutines ships its own rules.
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# ── Sealed classes & enums ────────────────────────────────────────────────
# SyncResult, AuthState, SaveStatus etc. are sealed classes used in when()
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Suppress common warnings from transitive dependencies ─────────────────
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.sun.jna.**
