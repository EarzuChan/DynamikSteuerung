# DynaCtrl

[English Version](README.md)

### 概述

本仓库实现了一个全面的音频响度分析与动态处理解决方案，专为Android平台设计。采用高性能的Kotlin/Native核心引擎，集成libsndfile音频库，并实现EBU R128响度计算标准。该库与ExoPlayer无缝集成，支持实时音频处理和响度标准化。

### 架构设计

项目采用三层模块化设计，确保最优性能和可维护性：

```
├── native/          # Kotlin/Native核心库
├── lib/             # Android封装库  
└── demo-app/        # 演示应用程序
```

### 技术实现

#### 1. 原生核心模块 (`native/`)

**Kotlin/Native与C互操作实现**

原生模块使用Kotlin/Native实现核心音频处理功能，编译为本机机器码，在多架构下实现最优性能。

**libsndfile集成方案**

- **头文件修改**：修改`native/include/sndfile.h`，为不透明的`SNDFILE`类型定义虚拟结构体`sf_priv_tag`，以支持绑定生成
- **C互操作配置**：在`native/build.gradle.kts`中配置各架构的cinterops和共享库编译设置
- **链接器配置**：正确配置各目标架构的libsndfile共享对象链接
- **桩定义文件**：`native/src/nativeInterop/cinterop/libsndfile.def`作为编译要求（空文件，实际配置在Gradle中）

**EBU R128响度算法实现**

位于`native/src/nativeMain/kotlin/Ebur128.kt`，提供：

- 轻量级、自包含的EBU R128实现
- 跨平台兼容性（支持Kotlin/Native和Kotlin/JVM）
- 符合标准的响度测量，仅使用Kotlin标准库
- 集成响度计算能力

```kotlin
// 核心响度计算实现
class LightweightEbuR128(channels: Int, sampleRate: Int) {
    fun addFrames(samples: FloatArray)
    fun getIntegratedLoudness(): Float
}
```

#### 2. 多架构编译系统

**Android架构支持**

构建系统自动编译四种Android架构的原生库：
- `arm64-v8a`（64位ARM）
- `armeabi-v7a`（32位ARM）
- `x86_64`（64位Intel）
- `x86`（32位Intel）

**自动化构建流水线**

在`lib/build.gradle.kts`中配置：
- 自动原生库编译
- 跨架构依赖管理
- 自动SO文件复制到Android库相应位置
- Gradle任务编排实现无缝构建

#### 3. Android库封装 (`lib/`)

**核心API接口**

```kotlin
class LightweightLoudnessAnalyzer {
    fun analyzeFile(audioFile: File): AudioLoudnessInfo
}
```

**ExoPlayer集成**

`DynamicsProcessor`类（`lib/src/main/kotlin/me/earzuchan/dynactrl/exoplayer/DynamicsProcessor.kt`）提供：

- 播放期间实时音频处理
- 响度标准化至-14 LUFS（广播标准）
- 通过`setCurrentTrackLoudness(info)`进行逐轨响度调整
- 与ExoPlayer音频处理流水线无缝集成

```kotlin
// 使用模式
val processor = DynamicsProcessor()
processor.setCurrentTrackLoudness(loudnessInfo)
// 应用到ExoPlayer音频处理器链
```

**数据模型**

定义在`lib/src/main/kotlin/me/earzuchan/dynactrl/models/Models.kt`中：

```kotlin
data class AudioLoudnessInfo(
    val lufs: Float,  // LUFS
)
```

#### 4. 演示应用程序 (`demo-app/`)

**Jetpack Compose实现**

演示应用展示实际使用场景：
- 基于文件的响度分析
- 响度标准化实时播放
- ExoPlayer集成演示
- 实时响度可视化
- 跨轨音频音量平衡

### 技术深度解析

#### C互操作绑定生成

libsndfile集成需要谨慎处理不透明C结构体。头文件的关键修改：

```c
// 原始：typedef struct sf_private_tag SNDFILE;
// 修改以支持Kotlin/Native绑定生成：
typedef struct sf_priv_tag {
    int dummy;  // 用于绑定生成的虚拟字段
} SNDFILE;
```

这允许Kotlin/Native编译器生成正确的FFI绑定，同时保持API兼容性。

#### EBU R128算法实现

实现遵循ITU-R BS.1770-4标准：

1. **预滤波**：K权重滤波器实现
2. **门控**：集成响度的绝对和相对门控
3. **窗口化**：瞬时测量的滑动窗口分析
4. **积分**：遵循标准的正确时域积分

#### 性能考量

- **原生编译**：Kotlin/Native消除音频处理的JVM开销
- **内存管理**：实时处理的高效缓冲区管理
- **架构优化**：通过原生编译实现平台特定优化
- **最小依赖**：轻量级实现减少APK大小影响

### 使用示例

```kotlin
// 初始化分析器
val analyzer = LightweightLoudnessAnalyzer()

// 分析音频文件
val audioFile = File("/path/to/audio.wav")
val loudnessInfo = analyzer.analyzeFile(audioFile)

// 与ExoPlayer集成：给您的ExoPlayer添加该处理器

// 应用到ExoPlayer
val processor = DynamicsProcessor()
processor.setCurrentTrackLoudness(loudnessInfo)
```

### 构建要求

- Kotlin/Native工具链
- Android NDK
- 预构建的libsndfile二进制SO
- Gradle 8.0+

### 目标应用

- 音频播放器：已被 零度音乐 采用，https://github.com/sky130/ZeroMusicApp
- 音乐流媒体服务
- 广播合规工具

......

### 许可证

Apache 2.0