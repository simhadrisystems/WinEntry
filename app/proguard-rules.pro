# ═══════════════════════════════════════════════════════════════════════════
# SimpleInventory — ProGuard / R8 Rules
# ═══════════════════════════════════════════════════════════════════════════
# These rules prevent R8 from removing or renaming classes that are accessed
# via reflection, serialization, or external APIs at runtime.
# ═══════════════════════════════════════════════════════════════════════════

# Preserve stack traces for debugging release crashes
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── App resources referenced in layouts ──────────────────────────────────
# R8 resource shrinking removes mipmap/ic_launcher if only referenced in
# XML layouts (not in code). Keep all app icon variants explicitly.
-keep class com.simple.simpleinventory.R$mipmap { *; }
-keep class com.simple.simpleinventory.R$drawable { *; }
-keep class com.simple.simpleinventory.R$layout { *; }
-keep class com.simple.simpleinventory.R$id { *; }
-keep class com.simple.simpleinventory.R$navigation { *; }
# Room entities, Firestore models and data classes use reflection for
# field access — R8 must not rename or remove their fields.

-keep class com.simple.simpleinventory.data.entity.** { *; }
-keep class com.simple.simpleinventory.data.repository.** { *; }
-keep class com.simple.simpleinventory.data.UserProfile { *; }
-keepclassmembers class com.simple.simpleinventory.** {
    public <init>(...);
}

# ── Room ─────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ── Firebase Auth ─────────────────────────────────────────────────────────
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.auth.internal.** { *; }
-keepnames class com.google.firebase.auth.GoogleAuthProvider { *; }

# ── Firebase Firestore ────────────────────────────────────────────────────
# Firestore uses reflection to map documents to data classes
-keep class com.google.firebase.firestore.** { *; }
-keepnames class com.google.firebase.firestore.** { *; }
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.ServerTimestamp <fields>;
}
# Keep all classes that Firestore deserializes into (toObject calls)
-keep class com.simple.simpleinventory.data.UserProfile { *; }

# ── Google Sign-In / GMS ─────────────────────────────────────────────────
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keepnames class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── Google Sign-In internal classes (Parcelable — R8 must not remove) ────
# These classes are serialized/deserialized via Android Parcel between
# processes during sign-in. R8 removes them because they appear unreferenced
# in code — but the system needs them at runtime via reflection.
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.auth.api.signin.internal.** { *; }
-keepclassmembers class com.google.android.gms.auth.api.signin.** {
    <init>(...);
    <fields>;
}
-keepclassmembers class com.google.android.gms.auth.api.signin.internal.** {
    <init>(...);
    <fields>;
}

# ── Google API Client (Sheets) ────────────────────────────────────────────
-keep class com.google.api.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.sheets.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class com.google.api.** {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.**
-dontwarn com.google.common.**

# ── Apache POI (Excel) ────────────────────────────────────────────────────
# POI uses Class.newInstance() to instantiate XML handlers via reflection.
# R8 removes constructors it can't see being called directly — which breaks
# POI's internal service loader pattern at runtime with InstantiationException.
# Keep ALL POI classes with ALL members including no-arg constructors.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class schemasMicrosoftComOfficeOffice.** { *; }
-keep class schemasMicrosoftComOfficeWord.** { *; }
-keep class schemasMicrosoftComVml.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.etsi.uri.** { *; }
# Keep ALL classes that extend or implement ANY POI type — these are the
# service implementations instantiated via Class.newInstance() at runtime.
-keep class * extends org.apache.poi.POIDocument { *; }
-keep class * extends org.apache.poi.ooxml.POIXMLDocumentPart { *; }
-keep class * extends org.apache.poi.ooxml.POIXMLRelation { *; }
-keep class * extends org.apache.xmlbeans.XmlObject { *; }
-keep class * extends org.apache.xmlbeans.impl.schema.SchemaTypeSystemImpl { *; }
# Critical: keep ALL classes that have a no-arg constructor and are in
# packages that POI uses for its type registry
-keepclassmembers class ** {
    public <init>();
}
-keepclassmembers class * extends org.apache.poi.** {
    public <init>();
    public <init>(...);
}
-keepclassmembers class * extends org.apache.xmlbeans.** {
    public <init>();
    public <init>(...);
}
# Prevent R8 from optimizing away the static initializers where POI
# registers its type implementations
-keepclasseswithmembers class * {
    static org.apache.poi.ooxml.POIXMLRelation *;
}
-keepnames class * implements org.apache.poi.ss.usermodel.**
-keepnames class * implements org.apache.poi.xssf.**
-keepnames class * implements org.apache.xmlbeans.**
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**
# POI references Java desktop classes not available on Android
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.imageio.**
-dontwarn com.graphbuilder.**
-dontwarn org.etsi.**

# ── WorkManager ───────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
# Keep SyncWorker specifically
-keep class com.simple.simpleinventory.sync.SyncWorker { *; }

# ── Navigation Component ──────────────────────────────────────────────────
-keepnames class androidx.navigation.** { *; }
-keep class * extends androidx.fragment.app.Fragment { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-dontwarn kotlin.**
-keepclasseswithmembers class * {
    @kotlin.Metadata <methods>;
}
# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
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
# Desktop Java classes referenced by POI/other libs — not available on Android
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.swing.**
-dontwarn javax.xml.stream.**
-dontwarn com.graphbuilder.**
-dontwarn org.etsi.**
-dontwarn org.slf4j.**

# ── R8 generated missing_rules (POI Microsoft schemas) ────────────────────
-dontwarn com.microsoft.schemas.office.office.CTCallout
-dontwarn com.microsoft.schemas.office.office.CTClipPath
-dontwarn com.microsoft.schemas.office.office.CTDiagram
-dontwarn com.microsoft.schemas.office.office.CTEquationXml
-dontwarn com.microsoft.schemas.office.office.CTExtrusion
-dontwarn com.microsoft.schemas.office.office.CTFill
-dontwarn com.microsoft.schemas.office.office.CTInk
-dontwarn com.microsoft.schemas.office.office.CTRegroupTable
-dontwarn com.microsoft.schemas.office.office.CTRules
-dontwarn com.microsoft.schemas.office.office.CTSkew
-dontwarn com.microsoft.schemas.office.office.CTStrokeChild
-dontwarn com.microsoft.schemas.office.office.STDiagramLayout
-dontwarn com.microsoft.schemas.office.office.STOLELinkType
-dontwarn com.microsoft.schemas.office.office.STOLEUpdateMode
-dontwarn com.microsoft.schemas.office.office.STScreenSize
-dontwarn com.microsoft.schemas.office.powerpoint.CTEmpty
-dontwarn com.microsoft.schemas.office.powerpoint.CTRel
-dontwarn com.microsoft.schemas.office.visio.x2012.main.AttachedToolbarsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.ColorsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.CpType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.CustomMenusFileType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.CustomToolbarsFileType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.DataType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.DocumentSheetType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.DynamicGridEnabledType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.EventListType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.FaceNamesType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.FldType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.ForeignDataType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.GlueSettingsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.HeaderFooterType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.IconType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.MasterShortcutType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.PpType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.ProtectBkgndsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.ProtectMastersType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.ProtectShapesType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.ProtectStylesType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.PublishSettingsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.RefByType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.SnapAnglesType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.SnapExtensionsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.SnapSettingsType
-dontwarn com.microsoft.schemas.office.visio.x2012.main.TpType
-dontwarn com.microsoft.schemas.office.word.CTBorder
-dontwarn com.microsoft.schemas.office.word.STHorizontalAnchor
-dontwarn com.microsoft.schemas.office.word.STVerticalAnchor
-dontwarn com.microsoft.schemas.office.word.STWrapSide
-dontwarn com.microsoft.schemas.office.x2006.digsig.STPositiveInteger
-dontwarn com.microsoft.schemas.office.x2006.digsig.STSignatureProviderUrl
-dontwarn com.microsoft.schemas.office.x2006.digsig.STSignatureText
-dontwarn com.microsoft.schemas.office.x2006.digsig.STVersion
-dontwarn com.microsoft.schemas.vml.CTArc
-dontwarn com.microsoft.schemas.vml.CTCurve
-dontwarn com.microsoft.schemas.vml.CTImage
-dontwarn com.microsoft.schemas.vml.CTPolyLine
-dontwarn com.microsoft.schemas.vml.STImageAspect
-dontwarn com.microsoft.schemas.vml.STStrokeArrowLength
-dontwarn com.microsoft.schemas.vml.STStrokeArrowWidth
-dontwarn com.microsoft.schemas.vml.STStrokeEndCap
-dontwarn com.microsoft.schemas.vml.STStrokeLineStyle
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn org.w3.x2000.x09.xmldsig.KeyInfoType
-dontwarn org.w3.x2000.x09.xmldsig.SignatureMethodType
-dontwarn org.w3.x2000.x09.xmldsig.TransformsType