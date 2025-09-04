use std::collections::VecDeque;
use std::f64::consts::PI;

mod processing;
mod jni;
mod analysis;

// Channel mapping constants
pub const EBUR128_UNUSED: i32 = 0;
pub const EBUR128_LEFT: i32 = 1;
pub const EBUR128_RIGHT: i32 = 2;
pub const EBUR128_CENTER: i32 = 3;
pub const EBUR128_LEFT_SURROUND: i32 = 4;
pub const EBUR128_RIGHT_SURROUND: i32 = 5;

// Mode constants
pub const EBUR128_MODE_I: usize = 5; // Integrated loudness
pub const EBUR128_MODE_S: usize = 3; // Short-term loudness
pub const EBUR128_MODE_M: usize = 1; // Momentary loudness
pub const EBUR128_MODE_LRA: usize = 11; // Loudness range

#[derive(Clone)]
pub struct GatingBlock {
    pub energy: f64,
}

#[derive(Clone)]
pub struct Ebur128State {
    pub mode: usize,
    pub sample_rate: usize,
    pub channels: usize,
    pub channel_map: Vec<i32>,

    // Filter coefficients
    pub a: Vec<f64>,
    pub b: Vec<f64>,

    // Filter state for each channel (5 coefficients)
    pub v: Vec<Vec<f64>>,

    // Audio buffer and management
    pub audio_data: Vec<f64>,
    pub audio_data_frames: usize,
    pub audio_data_index: usize,
    pub needed_frames: usize,

    // Gating blocks
    pub block_list: VecDeque<GatingBlock>,
    pub short_term_block_list: VecDeque<GatingBlock>,
    pub block_counter: usize,
    pub short_term_frame_counter: usize,
}

impl Ebur128State {
    pub fn new(channels: usize, sample_rate: usize, mode: usize) -> Result<Self, &'static str> {
        if !(mode & EBUR128_MODE_I == EBUR128_MODE_I ||
             mode & EBUR128_MODE_S == EBUR128_MODE_S ||
             mode & EBUR128_MODE_M == EBUR128_MODE_M) {
            return Err("Invalid mode: must include at least one measurement mode");
        }

        let audio_data_frames = if (mode & EBUR128_MODE_S) == EBUR128_MODE_S {
            sample_rate * 3
        } else if (mode & EBUR128_MODE_M) == EBUR128_MODE_M {
            sample_rate / 5 * 2
        } else {
            sample_rate / 5 * 2 // Default for integrated mode
        };

        let mut state = Ebur128State {
            mode,
            sample_rate,
            channels,
            channel_map: vec![0; channels],
            a: vec![0.0; 5],
            b: vec![0.0; 5],
            v: vec![vec![0.0; 5]; channels],
            audio_data: vec![0.0; audio_data_frames * channels],
            audio_data_frames,
            audio_data_index: 0,
            needed_frames: sample_rate / 5 * 2, // Start with 400ms
            block_list: VecDeque::new(),
            short_term_block_list: VecDeque::new(),
            block_counter: 0,
            short_term_frame_counter: 0,
        };

        // Initialize channel map
        Ebur128State::init_channel_map(&mut state)?;

        // Initialize filter
        Ebur128State::init_filter(&mut state)?;

        Ok(state)
    }

    fn init_channel_map(state: &mut Ebur128State) -> Result<(), &'static str> {
        for i in 0..state.channels {
            state.channel_map[i] = match i {
                0 => EBUR128_LEFT,
                1 => EBUR128_RIGHT,
                2 => EBUR128_CENTER,
                4 => EBUR128_LEFT_SURROUND,
                5 => EBUR128_RIGHT_SURROUND,
                _ => EBUR128_UNUSED,
            };
        }
        Ok(())
    }

    fn init_filter(state: &mut Ebur128State) -> Result<(), &'static str> {
        let f0 = 1681.974450955533;
        let g = 3.999843853973347;
        let q = 0.7071752369554196;

        let k = (PI * f0 / state.sample_rate as f64).tan();
        let vh = 10.0f64.powf(g / 20.0);
        let vb = vh.powf(0.4996667741545416);

        let mut b1 = [0.0; 3];
        let mut a1 = [1.0, 0.0, 0.0];
        let a0 = 1.0 + k / q + k * k;
        b1[0] = (vh + vb * k / q + k * k) / a0;
        b1[1] = 2.0 * (k * k - vh) / a0;
        b1[2] = (vh - vb * k / q + k * k) / a0;
        a1[1] = 2.0 * (k * k - 1.0) / a0;
        a1[2] = (1.0 - k / q + k * k) / a0;

        // Second filter (low-pass at 38 Hz)
        let f0_2 = 38.13547087602444;
        let q_2 = 0.5003270373238773;
        let k2 = (PI * f0_2 / state.sample_rate as f64).tan();

        let mut a2 = [1.0, 0.0, 0.0];
        a2[1] = 2.0 * (k2 * k2 - 1.0) / (1.0 + k2 / q_2 + k2 * k2);
        a2[2] = (1.0 - k2 / q_2 + k2 * k2) / (1.0 + k2 / q_2 + k2 * k2);

        // Combined filter coefficients
        state.b[0] = b1[0] * 1.0;
        state.b[1] = b1[0] * (-2.0) + b1[1] * 1.0;
        state.b[2] = b1[0] * 1.0 + b1[1] * (-2.0) + b1[2] * 1.0;
        state.b[3] = b1[1] * 1.0 + b1[2] * (-2.0);
        state.b[4] = b1[2] * 1.0;

        state.a[0] = a1[0] * a2[0];
        state.a[1] = a1[0] * a2[1] + a1[1] * a2[0];
        state.a[2] = a1[0] * a2[2] + a1[1] * a2[1] + a1[2] * a2[0];
        state.a[3] = a1[1] * a2[2] + a1[2] * a2[1];
        state.a[4] = a1[2] * a2[2];

        Ok(())
    }

    pub fn set_channel_map(&mut self, channel_map: &[i32]) {
        self.channel_map.copy_from_slice(channel_map);
    }
}