# Keep llama.cpp JNI binding entry points when you add the :llama module.
-keep class android.llama.cpp.** { *; }
-keep class **.LLamaAndroid { *; }

# PdfBox-Android probes for the optional JP2Android decoder with Class.forName
# and throws a controlled MissingImageReaderException when it is absent. The
# decoder is no longer published in the configured repositories, and Focal's
# PDF feature extracts text rather than rendering JPEG-2000 images.
-dontwarn com.gemalto.jp2.JP2Decoder
