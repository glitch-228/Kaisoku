    -optimizationpasses 8
-dontobfuscate
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment

-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** {
    public <init>(...);
    public protected *;
}

-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# Mihon extension support
# Hosted extension APKs depend on these shared host classes and on preserved
# generic signatures for Injekt's runtime type references.
-keepattributes Signature
-keepattributes Annotation
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes *Annotation*
-keepattributes kotlin.Metadata

-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keeppackagenames eu.kanade.tachiyomi.**
-keepclassmembers class eu.kanade.tachiyomi.** {
    public <init>(...);
    public protected *;
}

-keep class uy.kohesive.injekt.** { *; }
-keep interface uy.kohesive.injekt.** { *; }
-keeppackagenames uy.kohesive.injekt.**
-keepclassmembers class uy.kohesive.injekt.** {
    public <init>(...);
    public protected *;
}

-keep class org.koitharu.kotatsu.core.parser.mihon.** { *; }
-keeppackagenames org.koitharu.kotatsu.core.parser.mihon.**

-keep class rx.** { *; }
-keep interface rx.** { *; }
-dontwarn rx.**

-keep class keiyoushi.** { *; }
-keep interface keiyoushi.** { *; }
-dontwarn keiyoushi.**

# QuickJS (app.cash.quickjs) — only referenced by dynamically-loaded extensions, so R8 would
# otherwise strip it and release builds would fail with "Failed resolution of: Lapp/cash/quickjs/QuickJs;".
-keep class app.cash.quickjs.** { *; }
-keep interface app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keeppackagenames okhttp3.**
-keepclassmembers class okhttp3.** {
    public <init>(...);
}
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keeppackagenames okio.**
-dontwarn okio.**
-dontwarn okhttp3.**

# Kotlin runtime used by hosted Mihon extensions.
# Release builds must keep these host classes available because extensions
# link against the host app's stdlib/coroutines/serialization artifacts.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keeppackagenames kotlin.**
-dontwarn kotlin.**

-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

-keep class kotlinx.serialization.** { *; }
-keeppackagenames kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclassmembers class **$$serializer {
    *** INSTANCE;
}
# Hosted Mihon/Tachiyomi extensions may use kotlinx.serialization without the @Serializable
# annotation (e.g. hand-written KSerializer Companions). Keep Companion + serializer() lookup +
# nested descriptor classes referenced by $$serializer so dynamically-loaded extension code can
# still resolve serializers at runtime.
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

-keep class kotlinx.coroutines.** { *; }
-keeppackagenames kotlinx.coroutines.**
-dontwarn kotlinx.coroutines.**

# kaisoku-parsers public API.
# Plugin parsers (and reflection-discovered built-in parsers) call into the parsers
# public API through abstract/interface types. R8 has no static view of those calls
# and aggressively shrinks the ABI — that's what stripped MangaLoaderContext methods
# (including getDefaultUserAgent) in v9.7.7-2, causing #2/#3 to crash on every parser
# construction. Keep the whole surface so dynamic dispatch resolves correctly.
-keep public class org.koitharu.kotatsu.parsers.MangaLoaderContext { *; }
-keep public interface org.koitharu.kotatsu.parsers.MangaParser { *; }
-keep public interface org.koitharu.kotatsu.parsers.MangaParserAuthProvider { *; }
-keep public class org.koitharu.kotatsu.parsers.core.** { public protected *; }
-keep public class org.koitharu.kotatsu.parsers.model.** { public protected *; }
-keep public class org.koitharu.kotatsu.parsers.config.** { public protected *; }
-keep public class org.koitharu.kotatsu.parsers.network.** { public protected *; }
-keep public class org.koitharu.kotatsu.parsers.exception.** { public protected *; }
-keep public class org.koitharu.kotatsu.parsers.webview.** { public protected *; }
-keep public class org.koitharu.kotatsu.parsers.util.** { public protected *; }
-keep,allowobfuscation @org.koitharu.kotatsu.parsers.MangaSourceParser class * { *; }
-keep,allowobfuscation @org.koitharu.kotatsu.parsers.InternalParsersApi class * { *; }

# INVARIANT: keep every external library kaisoku-parsers links against. R8 keeps the parser classes
# as frozen entry points (above) but still optimizes/shrinks the libraries they call — it can't see
# the parser's call sites, so it rewrites/strips Kotlin default-arg synthetic constructors & methods
# (the trailing int-mask / DefaultConstructorMarker params), and the parser then hits a runtime
# NoSuchMethodError (release-only). Seen with MangaLoaderContext (v9.7.7-2, #2/#3) and with
# SparseArrayCompat (Paginator -> SparseArrayCompat()).
# Parser deps (kaisoku-parsers/build.gradle.kts): kotlinx.coroutines (kept below), okhttp3 + okio
# (kept above), jsoup (kept above), org.json (Android platform, never shrunk), androidx.collection
# (kept here). When kaisoku-parsers gains a new library dependency, add a keep for it here too.
-keep class androidx.collection.** { *; }
