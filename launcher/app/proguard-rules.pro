# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# jcifs-ng (SMB client): internally drives protocol state machines via reflection and its
# own SPI; R8 must keep the whole tree or SMB calls fail at runtime.
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# jcifs looks up MD4/MD5/SHA-512 by name at runtime (jcifs.util.Crypto); R8 cannot see those
# lookups and would strip the BouncyCastle digest implementations the protocol needs.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
