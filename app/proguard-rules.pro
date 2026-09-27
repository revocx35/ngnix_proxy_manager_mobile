# kotlinx.serialization, Navigation, OkHttp, coroutines and Compose ship their own R8 rules; the app needs
# no extra keep rules (models are (de)serialized through generated serializers, not reflection).

# Shrink and optimize, but don't rename: stack traces in the in-app crash reports stay readable without a
# mapping file, and nothing can break because a class or member name changed.
-dontobfuscate
