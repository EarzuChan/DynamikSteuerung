use crate::{Ebur128State, GatingBlock, EBUR128_LEFT_SURROUND, EBUR128_MODE_I, EBUR128_MODE_LRA, EBUR128_RIGHT_SURROUND, EBUR128_UNUSED};

// Audio processing and loudness calculation methods
impl Ebur128State {
    // Constants for loudness calculation
    const MINUS_EIGHT_DECIBELS: f64 = 0.15848931924611134;//10.0f64.powf(-8.0 / 10.0);
    const MINUS_TWENTY_DECIBELS: f64 = 0.01;//10.0f64.powf(-20.0 / 10.0);
    const ABS_THRESHOLD_ENERGY: f64 = 4.90907876152606E-71;

    /// Filter and process a frame of audio data
    pub fn filter_float(&mut self, src: &[f32], frames: usize) -> Result<(), &'static str> {
        if frames == 0 || src.len() < frames * self.channels {
            return Err("Invalid frame count or source buffer size");
        }

        let audio_data = &mut self.audio_data[self.audio_data_index..];

        for c in 0..self.channels {
            if self.channel_map[c] == EBUR128_UNUSED {
                continue;
            }

            for i in 0..frames {
                let input = src[i * self.channels + c] as f64;

                // Apply IIR filter (4th order)
                self.v[c][0] = input - self.a[1] * self.v[c][1]
                                   - self.a[2] * self.v[c][2]
                                   - self.a[3] * self.v[c][3]
                                   - self.a[4] * self.v[c][4];

                let output = self.b[0] * self.v[c][0]
                            + self.b[1] * self.v[c][1]
                            + self.b[2] * self.v[c][2]
                            + self.b[3] * self.v[c][3]
                            + self.b[4] * self.v[c][4];

                audio_data[i * self.channels + c] = output;

                // Update filter state
                self.v[c][4] = self.v[c][3];
                self.v[c][3] = self.v[c][2];
                self.v[c][2] = self.v[c][1];
                self.v[c][1] = self.v[c][0];
            }
        }
        Ok(())
    }

    /// Add frames of audio data for processing
    pub fn add_frames_float(&mut self, src: &[f32], frames: usize) -> Result<(), &'static str> {
        let mut src_index = 0;
        let mut remaining_frames = frames;

        while remaining_frames > 0 {
            if remaining_frames >= self.needed_frames {
                // Process a full block
                self.filter_float(&src[src_index..], self.needed_frames)?;
                src_index += self.needed_frames * self.channels;
                remaining_frames -= self.needed_frames;

                self.audio_data_index += self.needed_frames * self.channels;

                // Calculate gating block for integrated loudness
                if (self.mode & EBUR128_MODE_I) == EBUR128_MODE_I {
                    self.calc_gating_block(self.sample_rate / 5 * 2)?;
                }

                // Handle short-term loudness range
                if (self.mode & EBUR128_MODE_LRA) == EBUR128_MODE_LRA {
                    self.short_term_frame_counter += self.needed_frames;
                    if self.short_term_frame_counter == self.sample_rate * 3 {
                        if let Some(energy) = self.energy_shortterm()? {
                            let block = GatingBlock { energy };
                            self.short_term_block_list.push_back(block);
                        }
                        self.short_term_frame_counter = self.sample_rate * 2;
                    }
                }

                self.needed_frames = self.sample_rate / 5; // 200ms blocks after first

                if self.audio_data_index == self.audio_data_frames * self.channels {
                    self.audio_data_index = 0;
                }
            } else {
                // Process remaining frames
                self.filter_float(&src[src_index..], remaining_frames)?;
                self.audio_data_index += remaining_frames * self.channels;

                if (self.mode & EBUR128_MODE_LRA) == EBUR128_MODE_LRA {
                    self.short_term_frame_counter += remaining_frames;
                }

                self.needed_frames -= remaining_frames;
                remaining_frames = 0;
            }
        }
        Ok(())
    }

    /// Calculate a gating block
    fn calc_gating_block(&mut self, frames_per_block: usize) -> Result<(), &'static str> {
        let mut sum = 0.0;

        for c in 0..self.channels {
            if self.channel_map[c] == EBUR128_UNUSED {
                continue;
            }

            let mut channel_sum = 0.0;
            let channel_data = if self.audio_data_index < frames_per_block * self.channels {
                // Wrap around data buffer
                let first_part = &self.audio_data[..self.audio_data_index / self.channels];
                let second_part_start = self.audio_data_frames -
                                       (frames_per_block - self.audio_data_index / self.channels);
                let second_part = &self.audio_data[second_part_start..self.audio_data_frames];

                for &sample in first_part {
                    channel_sum += sample * sample;
                }
                for &sample in second_part {
                    channel_sum += sample * sample;
                }
            } else {
                let start_idx = self.audio_data_index / self.channels - frames_per_block;
                for i in start_idx..start_idx + frames_per_block {
                    channel_sum += self.audio_data[i * self.channels + c] *
                                 self.audio_data[i * self.channels + c];
                }
            };

            // Apply channel weighting for surround channels
            if self.channel_map[c] == EBUR128_LEFT_SURROUND ||
               self.channel_map[c] == EBUR128_RIGHT_SURROUND {
                channel_sum *= 1.41;
            }
            sum += channel_sum;
        }

        sum /= frames_per_block as f64;

        if sum >= Self::ABS_THRESHOLD_ENERGY {
            let block = GatingBlock { energy: sum };
            self.block_list.push_back(block);
            self.block_counter += 1;
        }

        Ok(())
    }

    /// Get integrated loudness of the whole programme
    pub fn loudness_global(&mut self) -> Option<f64> {
        if (self.mode & EBUR128_MODE_I) != EBUR128_MODE_I {
            return None;
        }

        self.gated_loudness(&[], None)
    }

    /// Get integrated loudness of the last segment
    pub fn loudness_segment(&mut self) -> Option<f64> {
        if (self.mode & EBUR128_MODE_I) != EBUR128_MODE_I {
            return None;
        }

        self.gated_loudness(&[], Some(self.block_counter))
    }

    /// Calculate gated loudness
    fn gated_loudness(&mut self, additional_states: &[&Ebur128State], block_count_limit: Option<usize>) -> Option<f64> {
        let mut all_blocks = Vec::new();

        // Collect our own blocks
        for block in &self.block_list {
            all_blocks.push(block.energy);
        }

        // Collect blocks from additional states
        for state in additional_states {
            for block in &state.block_list {
                all_blocks.push(block.energy);
            }
        }

        if all_blocks.is_empty() {
            return None;
        }

        // Find relative threshold (loudest 10% of blocks)
        let threshold_index = (all_blocks.len() as f64 * 0.9).floor() as usize;
        if threshold_index >= all_blocks.len() {
            return None;
        }

        all_blocks.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let relative_threshold = all_blocks[threshold_index] * Self::MINUS_EIGHT_DECIBELS;

        // Calculate gated loudness from blocks above threshold
        let mut gated_energy = 0.0;
        let mut above_thresh_count = 0;

        let limit = block_count_limit.unwrap_or(all_blocks.len());
        for &energy in all_blocks.iter().rev() {
            if above_thresh_count >= limit {
                break;
            }
            if energy >= relative_threshold {
                gated_energy += energy;
                above_thresh_count += 1;
            }
        }

        if above_thresh_count == 0 {
            return None;
        }

        gated_energy /= above_thresh_count as f64;
        Some(Self::energy_to_loudness(gated_energy))
    }

    /// Calculate short-term loudness energy
    fn energy_shortterm(&self) -> Result<Option<f64>, &'static str> {
        if self.sample_rate * 3 > self.audio_data_frames {
            return Ok(None);
        }
        Ok(Some(self.energy_in_interval(self.sample_rate * 3)?))
    }

    /// Calculate energy in a specific time interval
    fn energy_in_interval(&self, interval_frames: usize) -> Result<f64, &'static str> {
        if interval_frames > self.audio_data_frames {
            return Err("Interval too large for buffer");
        }

        let mut loudness = 0.0;
        self.calc_gating_block_with_output(interval_frames, &mut loudness)?;
        Ok(loudness)
    }

    /// Calculate gating block and return energy in output parameter
    fn calc_gating_block_with_output(&self, frames_per_block: usize, output: &mut f64) -> Result<(), &'static str> {
        let mut sum = 0.0;

        for c in 0..self.channels {
            if self.channel_map[c] == EBUR128_UNUSED {
                continue;
            }

            let mut channel_sum = 0.0;
            let channel_data = if self.audio_data_index < frames_per_block * self.channels {
                let first_part = &self.audio_data[..self.audio_data_index / self.channels];
                let second_part_start = self.audio_data_frames -
                                       (frames_per_block - self.audio_data_index / self.channels);
                let second_part = &self.audio_data[second_part_start..self.audio_data_frames];

                for &sample in first_part {
                    channel_sum += sample * sample;
                }
                for &sample in second_part {
                    channel_sum += sample * sample;
                }
            } else {
                let start_idx = self.audio_data_index / self.channels - frames_per_block;
                for i in start_idx..start_idx + frames_per_block {
                    channel_sum += self.audio_data[i * self.channels + c] *
                                 self.audio_data[i * self.channels + c];
                }
            };

            if self.channel_map[c] == EBUR128_LEFT_SURROUND ||
               self.channel_map[c] == EBUR128_RIGHT_SURROUND {
                channel_sum *= 1.41;
            }
            sum += channel_sum;
        }

        sum /= frames_per_block as f64;
        *output = sum;
        Ok(())
    }

    /// Convert energy to loudness in LUFS
    fn energy_to_loudness(energy: f64) -> f64 {
        10.0 * energy.log10() - 0.691
    }

    /// Start a new segment
    pub fn start_new_segment(&mut self) {
        self.block_counter = 0;
        self.needed_frames = self.sample_rate / 5 * 2; // Reset to 400ms
        self.audio_data_index = 0;
        self.audio_data.fill(0.0);
        self.short_term_frame_counter = 0;
    }
}

// Loudness info for audio files and normalization
#[derive(Clone, Debug)]
pub struct AudioLoudnessInfo {
    pub lufs: f32,
    pub sample_rate: u32,
    pub channels: u32,
    pub duration_seconds: f32,
    pub target_scale: f32,
}

impl AudioLoudnessInfo {
    pub fn new(lufs: f64, sample_rate: usize, channels: usize, duration_seconds: f32) -> Self {
        // Calculate target scale for -18 dB LUFS reference
        let reference_loudness = -18.0;
        let target_scale = if lufs.is_finite() && lufs > -70.0 {
            10.0f64.powf((reference_loudness - lufs) / 20.0) as f32
        } else {
            1.0 // Default scale if measurement failed
        };

        Self {
            lufs: lufs as f32,
            sample_rate: sample_rate as u32,
            channels: channels as u32,
            duration_seconds,
            target_scale,
        }
    }
}