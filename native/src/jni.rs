use jni::JNIEnv;
use jni::objects::GlobalRef;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jfloat, jint, jlong};
use std::sync::Mutex;

static LOGGER: Mutex<Option<GlobalRef>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_me_earzuchan_dynactrl_DynaCtrlKt_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    logger: JObject,
) -> jint {
    if !logger.is_null() {
        match env.new_global_ref(logger) {
            Ok(global_ref) => {
                let mut logger_lock = LOGGER.lock().unwrap();
                *logger_lock = Some(global_ref);

                // log_to_java(&mut env, "RUST_TEST", "Test the fxxking logger");

                1 // ALRIGHT
            }
            Err(_e) => 0, // STH went WRONG
        }
    } else {
        -1 // LOGGR is NULL
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_me_earzuchan_dynactrl_DynaCtrlKt_nativeTestLogger(
    mut env: JNIEnv,
    _class: JClass,
) -> jint {
   log_to_java(&mut env, "RUST_TEST", "Test the fxxking logger")
}

fn log_to_java(env: &mut JNIEnv, tag: &str, message: &str) -> i32 {
    let logger_lock = LOGGER.lock().unwrap();
    if let Some(logger_obj) = &*logger_lock {
        if let Ok(j_tag) = env.new_string(tag) {
            if let Ok(j_msg) = env.new_string(message) {
                let result = env.call_method(
                    logger_obj,
                    "log",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    &[JValue::Object(&j_tag), JValue::Object(&j_msg)],
                );

                // 检查是否有异常
                if result.is_err() {
                    0 // STH went WRONG
                } else {
                    1 // OKAY
                }
            } else {
                -2 // NO MSG
            }
        } else {
            -2 // NO TAG
        }
    } else {
        -1 // NO LOGGR
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_me_earzuchan_dynactrl_utilities_LightweightLoudnessAnalyzer_nativeAnalyzeFile(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jlong {
    // Convert Java string to Rust string
    let file_path_str = match env.get_string(&file_path) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return -1,
    };

    // Analyze the actual file
    match crate::analysis::analyze_audio_file(&file_path_str) {
        Ok(info) => Box::into_raw(Box::new(info)) as jlong,
        Err(e) => {
            log_to_java(&mut env, "RUST_ERR", &format!("{}", e));
            0
        } // Return null pointer on error
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_me_earzuchan_dynactrl_models_AudioLoudnessInfo_nativeGetLufs(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jfloat {
    if ptr == 0 {
        return -70.0; // Return a default quiet value
    }

    let info = &*(ptr as *const crate::processing::AudioLoudnessInfo);
    info.lufs
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_me_earzuchan_dynactrl_models_AudioLoudnessInfo_nativeGetTargetScale(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jfloat {
    if ptr == 0 {
        return 1.0; // Return default scale
    }

    let info = &*(ptr as *const crate::processing::AudioLoudnessInfo);
    info.target_scale
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_me_earzuchan_dynactrl_models_AudioLoudnessInfo_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        let _ = Box::from_raw(ptr as *mut crate::processing::AudioLoudnessInfo);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_me_earzuchan_dynactrl_exoplayer_DynamicsProcessor_nativeProcessAudio(
    _env: JNIEnv,
    _class: JClass,
    loudness_info_ptr: jlong,
    input_buffer: *mut f32,
    output_buffer: *mut f32,
    sample_count: jint,
) -> jboolean {
    if loudness_info_ptr == 0 || input_buffer.is_null() || output_buffer.is_null() {
        return 0; // JNI_FALSE
    }

    let info = &*(loudness_info_ptr as *const crate::processing::AudioLoudnessInfo);
    let scale = info.target_scale as f64;

    let input_slice = std::slice::from_raw_parts(input_buffer, sample_count as usize);
    let output_slice = std::slice::from_raw_parts_mut(output_buffer, sample_count as usize);

    // Apply loudness normalization
    for i in 0..(sample_count as usize) {
        output_slice[i] = (input_slice[i] as f64 * scale) as f32;
    }

    1 // JNI_TRUE
}
