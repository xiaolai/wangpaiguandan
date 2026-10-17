# WI-12: extra R8 rules for the r8check build only (never release). The on-device test runner and
# the tests themselves (androidx.test, JUnit, Compose testing) call library classes that live in the
# app APK; release shrinking removes the ones the app never calls, and the test APK then fails to
# load (seen: androidx.tracing.Trace, kotlin.LazyKt). So r8check keeps third-party libraries whole,
# EXCEPT this project's code and kotlinx-serialization: those two are shrunk, optimized and renamed
# exactly as in release, which is what R8SerializationTest checks.
-keep class !cn.wpgd.**,!kotlinx.serialization.** { *; }

# Entry points R8SerializationTest calls directly from the test APK. R8 would otherwise inline or
# drop their singleton fields (seen: NoSuchFieldError INSTANCE / Companion), which breaks the test's
# linkage, not the app. Only these facades are pinned; the generated `$serializer` classes and the
# serialization library behind them are shrunk as in release.
-keep class cn.wpgd.app.session.wire.WireCodec { *; }
-keep class cn.wpgd.app.table.RuleProfiles { *; }
-keep class cn.wpgd.app.table.RuleProfiles$Companion { *; }
-keep class cn.wpgd.app.core.match.MatchReducer { *; }
-keep class cn.wpgd.app.core.match.MatchStateCodec { *; }
-keep class cn.wpgd.app.core.match.MatchProjectionCodec { *; }
-keep class cn.wpgd.app.core.match.MatchProjector { *; }
-keep class cn.wpgd.app.core.match.MatchCommand$DealNext { *; }
-keep class cn.wpgd.app.core.match.MatchReduceResult$Accepted { *; }
-keep class cn.wpgd.app.core.state.SplitMix64 { *; }
-keep class cn.wpgd.app.core.state.Seat { *; }
# aSoloTableDealsAndTheClientDecodesItsViewUnderR8: the production graph, the owner and the getters
# of the state it publishes. The host, wire, channel and client code it runs stay as in release.
-keep class cn.wpgd.app.WpgdApplication { *; }
-keep class cn.wpgd.app.AppGraph { *; }
-keep class cn.wpgd.app.owner.SessionOwner { *; }
-keep class cn.wpgd.app.owner.OwnerState { *; }
-keep class cn.wpgd.app.owner.TableSnapshot { *; }
-keep class cn.wpgd.app.data.Nickname { *; }
-keep class cn.wpgd.app.data.Nickname$Companion { *; }
-keep class cn.wpgd.app.data.NicknameResult$Valid { *; }
-keep class cn.wpgd.app.core.match.MatchMemberView { *; }
-keep class cn.wpgd.app.core.state.MemberView { *; }
