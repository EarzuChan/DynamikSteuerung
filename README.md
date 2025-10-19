# Tetsu

[中文版](README_CN.md)

### Overview

This repository implements a comprehensive audio loudness analysis and dynamics processing solution for Android, featuring a high-performance Kotlin/Native core with libsndfile integration and EBU R128 loudness calculation. The library provides seamless integration with ExoPlayer for real-time audio processing and loudness normalization.

### Architecture

The project consists of three main modules designed for optimal performance and modularity:

```
├── native/          # Kotlin/Native core library
├── lib/             # Android wrapper library  
└── demo-app/        # Demonstration application
```

### Technical Implementation

#### 1. Native Core (`native/`)

**Kotlin/Native Implementation with C Interop**

The native module implements the core audio processing functionality using Kotlin/Native, enabling compilation to native machine code for optimal performance across multiple architectures.

**libsndfile Integration**

- **Header Modification**: Modified `native/include/sndfile.h` to enable proper binding generation by defining a dummy structure `sf_priv_tag` for the opaque `SNDFILE` type
- **C Interop Configuration**: Configured in `native/build.gradle.kts` with architecture-specific cinterops and shared library compilation settings
- **Linker Configuration**: Properly configured to link against libsndfile shared objects for each target architecture
- **Stub Definition**: `native/src/nativeInterop/cinterop/libsndfile.def` serves as a compilation requirement (empty file, actual configuration in Gradle)

**EBU R128 Loudness Implementation**

Located in `native/src/nativeMain/kotlin/EbuR128.kt`, this module provides:

- Lightweight, self-contained EBU R128 implementation
- Cross-platform compatibility (works on both Kotlin/Native and Kotlin/JVM)
- Standards-compliant loudness measurement using only Kotlin standard library
- Integrated loudness calculation capability

```kotlin
// Core loudness calculation implementation
class LightweightEbuR128(channels: Int, sampleRate: Int) {
    fun addSamples(samples: FloatArray)
    fun getIntegratedLoudness(): Float
}
```

#### 2. Multi-Architecture Compilation

**Android Architecture Support**

The build system automatically compiles native libraries for four Android architectures:
- `arm64-v8a` (64-bit ARM)
- `armeabi-v7a` (32-bit ARM)
- `x86_64` (64-bit Intel)
- NO `x86`: Outdated!

**Automated Build Pipeline**

Configured in `lib/build.gradle.kts`:
- Automatic native library compilation
- Cross-architecture dependency management
- Automated SO file copying to appropriate Android library locations
- Gradle task orchestration for seamless builds

#### 3. Android Library Wrapper (`lib/`)

**Core API Interface**

```kotlin
object LightweightLoudnessAnalyzer {
    fun analyzeFile(audioFile: File): AudioLoudnessInfo
}
```

**ExoPlayer Integration**

The `DynamicsProcessor` class (`lib/src/main/kotlin/me/earzuchan/tetsu/exoplayer/DynamicsProcessor.kt`) provides:

- Real-time audio processing during playback
- Loudness normalization to -14 LUFS (broadcast standard)
- Per-track loudness adjustment via `setCurrentTrackLoudness(info)`
- Seamless integration with ExoPlayer's audio processing pipeline

```kotlin
// Usage pattern
val processor = DynamicsProcessor()
processor.setCurrentTrackLoudness(loudnessInfo)
// Apply to ExoPlayer audio processor chain
```

**Data Models**

Defined in `lib/src/main/kotlin/me/earzuchan/tetsu/models/Models.kt`:

```kotlin
data class AudioLoudnessInfo(
    val lufs: Float,  // LUFS
)
```

#### 4. Demonstration Application (`demo-app/`)

**Jetpack Compose Implementation**

The demo application showcases practical usage:
- File-based loudness analysis
- Real-time playback with loudness normalization
- ExoPlayer integration demonstration
- Audio volume balancing across tracks

### Technical Deep Dive

#### C Interop Binding Generation

The libsndfile integration requires careful handling of opaque C structures. The key modification in the header file:

```c
// Original: typedef struct sf_private_tag SNDFILE;
// Modified to enable Kotlin/Native binding generation:
typedef struct sf_priv_tag {
    int dummy;  // Dummy field for binding generation
} SNDFILE;
```

This allows the Kotlin/Native compiler to generate proper FFI bindings while maintaining API compatibility.

#### EBU R128 Algorithm Implementation

The implementation follows ITU-R BS.1770-4 standard:

1. **Pre-filtering**: K-weighting filter implementation
2. **Gating**: Absolute and relative gating for integrated loudness
3. **Windowing**: Sliding window analysis for momentary measurements
4. **Integration**: Proper time-domain integration following the standard

#### Performance Considerations

- **Native Compilation**: Kotlin/Native eliminates JVM overhead for audio processing
- **Memory Management**: Efficient buffer management for real-time processing
- **Architecture Optimization**: Platform-specific optimizations through native compilation
- **Minimal Dependencies**: Lightweight implementation reduces APK size impact

### Usage Example

```kotlin
// Analyze audio file
val audioFile = File("/path/to/audio.wav")
val loudnessInfo = LightweightLoudnessAnalyzer.analyzeFile(audioFile.absolutePath)

// Integrate with ExoPlayer: Add the processor to your ExoPlayer

// Apply to ExoPlayer
val processor = DynamicsProcessor()
processor.setCurrentTrackLoudness(loudnessInfo)
```

### Build Requirements

- Kotlin/Native toolchain
- Android NDK
- libsndfile prebuilt SO binaries
- Gradle 8+

### Target Applications

- Audio players: Already adopted by ZeroMusic, https://github.com/sky130/ZeroMusicApp
- Music streaming services
- Broadcast compliance tools

......

### License

Apache 2.0