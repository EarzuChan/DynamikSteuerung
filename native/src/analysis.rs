use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use crate::processing::{AudioLoudnessInfo};
use crate::EBUR128_MODE_I;
use crate::Ebur128State;

// Simple WAV file header structure
#[repr(C, packed)]
#[derive(Clone, Copy, Debug)]
struct WavHeader {
    riff: [u8; 4],
    file_size: u32,
    wave: [u8; 4],
    fmt: [u8; 4],
    fmt_size: u32,
    format: u16,
    channels: u16,
    sample_rate: u32,
    byte_rate: u32,
    block_align: u16,
    bits_per_sample: u16,
    data: [u8; 4],
    data_size: u32,
}

impl WavHeader {
    fn is_valid(&self) -> bool {
        &self.riff == b"RIFF" && &self.wave == b"WAVE" && &self.fmt == b"fmt " && &self.data == b"data"
    }
}

/// Analyze an audio file for loudness
pub fn
analyze_audio_file(file_path: &str) -> Result<AudioLoudnessInfo, String> {
    let mut file = File::open(file_path)
        .map_err(|e| format!("Failed to open file {}: {}", file_path, e))?;

    // Try to read file as WAV first
    return match analyze_wav_file(&mut file) {
        Ok(s) => Ok(s),
        Err(e) => Err(format!("Failed to analyze WAV audio file: {}", e)),
    };

    // BREAK THE KODE

    // Reset file position for other formats
    file.seek(SeekFrom::Start(0)).map_err(|e| format!("Failed to seek file: {}", e))?;

    // For now, return a placeholder for unsupported formats
    // In a full implementation, you'd add MP3, FLAC, etc. support
    Err(format!("Unsupported file format for {}", file_path))
}

fn analyze_wav_file(file: &mut File) -> Result<AudioLoudnessInfo, String> {
    // Read WAV header
    let mut header_bytes = [0u8; size_of::<WavHeader>()];
    file.read_exact(&mut header_bytes)
        .map_err(|e| format!("Failed to read WAV header: {}", e))?;

    let header: WavHeader = unsafe { std::mem::transmute(header_bytes) };

    if !header.is_valid() {
        return Err("Invalid WAV header".to_string());
    }

    if header.format != 1 {
        let format_value = header.format;
        return Err(format!("Unsupported WAV format: {}", format_value));
    }

    if header.bits_per_sample != 16 && header.bits_per_sample != 24 && header.bits_per_sample != 32 {
        let bits_per_sample_value = header.bits_per_sample;
        return Err(format!("Unsupported bit depth: {}", bits_per_sample_value));
    }

    let channels = header.channels as usize;
    let sample_rate = header.sample_rate as usize;
    let duration_seconds = header.data_size as f32 / header.byte_rate as f32;

    // Initialize EBU-R128 analyzer
    let mut state = Ebur128State::new(channels, sample_rate, EBUR128_MODE_I)
        .map_err(|e| format!("Failed to initialize analyzer: {}", e))?;

    // Read and process audio data
    let bytes_per_sample = (header.bits_per_sample / 8) as usize;
    let samples_to_read = (header.data_size / (bytes_per_sample * channels) as u32) as usize;

    // Process audio in chunks
    let chunk_size = sample_rate; // 1 second chunks
    let mut buffer = vec![0u8; chunk_size * channels * bytes_per_sample];

    let mut total_samples_processed = 0;

    while total_samples_processed < samples_to_read {
        let samples_to_process = std::cmp::min(chunk_size, samples_to_read - total_samples_processed);
        let bytes_to_read = samples_to_process * channels * bytes_per_sample;

        if bytes_to_read > buffer.len() {
            buffer.resize(bytes_to_read, 0);
        }

        let bytes_read = file.read(&mut buffer[..bytes_to_read])
            .map_err(|e| format!("Failed to read audio data: {}", e))?;

        if bytes_read == 0 {
            break;
        }

        let actual_samples = bytes_read / (channels * bytes_per_sample);

        // Convert to float buffer
        let mut float_buffer = vec![0.0f32; actual_samples * channels];

        match header.bits_per_sample {
            16 => {
                for i in 0..actual_samples * channels {
                    let start = i * 2;
                    let sample = i16::from_le_bytes([buffer[start], buffer[start + 1]]);
                    float_buffer[i] = (sample as f32) / 32768.0;
                }
            },
            24 => {
                for i in 0..actual_samples * channels {
                    let start = i * 3;
                    let sample = i32::from_le_bytes([buffer[start], buffer[start + 1], buffer[start + 2], 0]);
                    float_buffer[i] = (sample as f32) / 8388608.0;
                }
            },
            32 => {
                for i in 0..actual_samples * channels {
                    let start = i * 4;
                    let sample = f32::from_le_bytes([buffer[start], buffer[start + 1], buffer[start + 2], buffer[start + 3]]);
                    float_buffer[i] = sample;
                }
            },
            _ => return Err("Unsupported bit depth".to_string()),
        }

        // Add frames to analyzer
        state.add_frames_float(&float_buffer, actual_samples)
            .map_err(|e| format!("Failed to process audio frames: {}", e))?;

        total_samples_processed += actual_samples;
    }

    // Calculate final loudness
    let global_loudness = state.loudness_global()
        .unwrap_or(-70.0); // Default to quiet if no measurement

    Ok(AudioLoudnessInfo::new(
        global_loudness,
        sample_rate,
        channels,
        duration_seconds,
    ))
}