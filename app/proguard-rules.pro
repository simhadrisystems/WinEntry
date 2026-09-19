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
# @Entity classes are already fully kept below (Room section); repositories
# are plain Kotlin wrappers with no reflection or serialization.

# ── Room ─────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

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
# The two -keepclassmembers blocks that used to follow (repeating <init>/
# <fields> for the same two packages) were fully redundant — the -keep
# rules above already retain every member via `{ *; }`. Verified play-
# services-auth-20.7.0.aar ships zero bundled consumer proguard rules
# (only META-INF/MANIFEST.MF), so the internal.** keep above is the only
# protection for its ~25 Parcelable internal classes — do not narrow it
# without testing sign-in end to end.

# ── Apache POI (Excel) ────────────────────────────────────────────────────
# POI uses Class.newInstance() to instantiate XML handlers via reflection.
# R8 removes constructors it can't see being called directly — which breaks
# POI's internal service loader pattern at runtime with InstantiationException.
#
# IMPORTANT: do NOT use the wildcard "-keep class org.apache.poi.** { *; }".
# XSLF (PowerPoint/SVG) is bundled in the same jar but is unused here.
# Its SVGUserAgent.getViewbox() returns java.awt.geom.Rectangle2D which does
# not exist on Android. R8 emits an un-suppressable "type check" verifier
# diagnostic (distinct from missing-class warnings, unaffected by -dontwarn)
# for every kept class whose method signatures reference an unresolvable type.
# The fix: keep only the packages needed for XSSF (xlsx) and omit the XSLF,
# HSLF, XWPF, HWPF, EMF, WMF packages that reference java.awt.*.
-keep class org.apache.poi.ss.** { *; }
-keep class org.apache.poi.xssf.** { *; }
-keep class org.apache.poi.ooxml.** { *; }
-keep class org.apache.poi.openxml4j.** { *; }
-keep class org.apache.poi.util.** { *; }
-keep class org.apache.poi.poifs.** { *; }
-keep class org.apache.poi.common.** { *; }
-keep class org.apache.poi.ddf.** { *; }
-keep class org.apache.poi.extractor.** { *; }
# Intentionally NOT kept (unused, reference java.awt.* which isn't on Android,
# or unreferenced by this app's spreadsheet-only Excel I/O — verified
# 2026-09-19: no XWPFDocument/HWPFDocument/comment/drawing usage anywhere in
# utils/*ExcelHelper*.kt, and schemasMicrosoftComOfficeWord/Vml match zero
# classes in this project's actual poi-ooxml-lite jar, so those two keeps
# were already no-ops):
#   org.apache.poi.xslf.**  – PowerPoint OOXML  (SVGUserAgent → java.awt.geom.*)
#   org.apache.poi.hslf.**  – Legacy PowerPoint
#   org.apache.poi.xwpf.**  – Word OOXML
#   org.apache.poi.hwpf.**  – Legacy Word
#   org.apache.poi.hemf.**  – Enhanced MetaFile
#   org.apache.poi.hwmf.**  – Windows MetaFile
#   org.apache.poi.wp.**    – shared Word/PowerPoint text infra
#   org.apache.poi.hssf.**  – legacy binary .xls. Every import path in this
#     app (ExcelHelper/PurchaseExcelHelper/DailyStockImportHelper/
#     QuickSaleExcelHelper) instantiates XSSFWorkbook directly now — the one
#     holdout, ExcelHelper.importProducts(), used the auto-detecting
#     WorkbookFactory.create() until 2026-09-19, which is what actually kept
#     HSSF reachable regardless of this keep rule. Every file picker in the
#     app is also hard-locked to the OOXML/.xlsx MIME type, so .xls was never
#     reachable through the UI. If a future import path needs real .xls
#     support, re-add both the WorkbookFactory.create() call and this keep.
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class schemasMicrosoftComOfficeOffice.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.etsi.uri.** { *; }
# Keep ALL classes that extend or implement ANY POI type — these are the
# service implementations instantiated via Class.newInstance() at runtime.
-keep class * extends org.apache.poi.POIDocument { *; }
-keep class * extends org.apache.poi.ooxml.POIXMLDocumentPart { *; }
-keep class * extends org.apache.poi.ooxml.POIXMLRelation { *; }
-keep class * extends org.apache.xmlbeans.XmlObject { *; }
-keep class * extends org.apache.xmlbeans.impl.schema.SchemaTypeSystemImpl { *; }
# The blanket "public <init>() for every class in the app" rule that used
# to stand here was verified (2026-09-18, by unzipping the actual
# poi-ooxml-lite:5.2.5 / xmlbeans:5.2.0 jars this project depends on) to be
# based on a wrong package name — "schemaorg_apache_xmlbeans.system.sXXXXXXXX"
# does not exist in these jars. The real reflectively-instantiated schema
# holder classes are the fixed (non-random) ones kept below:
#   org/apache/poi/schemas/ooxml/system/ooxml/TypeSystemHolder.class
#   org/apache/xmlbeans/metadata/system/{sXMLCONFIG,sXMLLANG,sXMLSCHEMA,sXMLTOOLS}/TypeSystemHolder.class
#   org/apache/xmlbeans/impl/schema/TypeSystemHolder.class
# This replaces the app-wide blanket that was masking this wrong assumption.
# Confirmed crash history if this is under-scoped: "IllegalArgumentException:
# class cc: java.lang.NoSuchMethodException: cc.<init> []" on Product Master
# Excel import — regression-test that exact flow before shipping.
-keep class org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder { *; }
-keep class org.apache.xmlbeans.metadata.system.** { *; }
-keep class org.apache.xmlbeans.impl.schema.TypeSystemHolder { *; }
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

# ── Commons Compress (transitive dependency of Apache POI's OOXML writer) ─
# org.apache.commons:commons-compress:1.25.0 backs POI's ZIP writing when
# saving .xlsx files. ExtraFieldUtils registers its ZIP "extra field" impl
# classes (X5455_ExtendedTimestamp, X000A_NTFS, AsiExtraField, etc.) via
# reflective no-arg instantiation in a static initializer. CONFIRMED CRASH
# without this keep: ExceptionInInitializerError / IllegalArgumentException
# "NoSuchMethodException: <init> []" on Excel export (ZipContentTypeManager
# .saveImpl -> ZipArchiveEntry.setTime -> ExtraFieldUtils) — this was also
# only incidentally covered before by the old app-wide "public <init>()"
# rule, undocumented until this crash surfaced after narrowing it.
-keepclassmembers class org.apache.commons.compress.archivers.zip.** {
    public <init>();
}
-dontwarn org.apache.commons.compress.**

# ── Log4j (transitive dependency of Apache POI 5.2.x) ─────────────────────
# POI 5.2.x logs internally via log4j-api. log4j's StatusLogger/
# PropertySource discovery reflectively instantiates implementation
# classes via Class.newInstance() during its static initializer
# (org.apache.logging.log4j.status.StatusLogger.<clinit>). log4j-api ships
# no consumer proguard rules of its own. CONFIRMED CRASH without this
# keep: InstantiationException at org.apache.logging.log4j.status.a.<clinit>
# the moment any POI Workbook is touched (Excel import/export) — this was
# previously protected only incidentally by a blanket app-wide
# "public <init>()" rule; when that was narrowed to remove app-wide
# over-protection, log4j needed its own explicit keep since it isn't part
# of the POI/xmlbeans/openxmlformats/schema package tree kept above.
-keep class org.apache.logging.log4j.** { *; }
-dontwarn org.apache.logging.log4j.**

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
-keepnames class androidx.navigation.** { *; }
-keep class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# ── Kotlin ────────────────────────────────────────────────────────────────
# No kotlin-reflect usage in this app — a blanket `kotlin.** { *; }` kept
# the entire stdlib (collections/text/sequences helpers included) fully
# unobfuscated and unshrunk for no reason; only Metadata + coroutines names
# are actually needed.
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