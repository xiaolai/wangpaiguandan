# WI-12: R8 rules of the release (and r8check) build, on top of proguard-android-optimize.txt.
#
# kotlinx-serialization: this project calls each generated serializer explicitly
# (`Foo.serializer()`, `Json.encodeToString(Foo.serializer(), …)`), never by reflection, and the
# library jar ships its own R8 rules (META-INF/com.android.tools/r8/…) for the generated
# `$serializer` classes and `Companion.serializer()`. So no project rule is needed here; the
# round trip under R8 is checked on a device by R8SerializationTest (`-Pwpgd.testBuildType=r8check`).
