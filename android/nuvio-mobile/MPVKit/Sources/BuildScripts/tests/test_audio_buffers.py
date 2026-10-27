import os
from pathlib import Path
import subprocess
import sys
import tempfile


source = Path(sys.argv[1]).read_text()
start = source.index("static int read_buffer(")
end = source.index("static int ao_read_data_locked(", start)
function = source[start:end]

harness = r"""
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define MP_FRAME_AUDIO 2
#define MP_FRAME_EOF 4
#define MPMIN(a, b) ((a) < (b) ? (a) : (b))
#define TA_FREEP(p) (*(p) = NULL)
#define MP_ERR(...) ((void)0)

struct mp_aframe {
    int samples, planes, stride, format;
    uint8_t **data;
};
struct mp_frame { int type; struct mp_aframe *data; };
struct input { void **pins; };
struct buffer_state {
    bool playing, paused;
    struct mp_aframe *pending;
    struct input *input;
};
struct ao {
    int num_planes, sstride, format, reloads;
    struct buffer_state *buffer_state;
};

static int mp_aframe_get_size(struct mp_aframe *f) { return f->samples; }
static int mp_aframe_get_planes(struct mp_aframe *f) { return f->planes; }
static size_t mp_aframe_get_sstride(struct mp_aframe *f) { return f->stride; }
static int mp_aframe_get_format(struct mp_aframe *f) { return f->format; }
static uint8_t **mp_aframe_get_data_ro(struct mp_aframe *f) { return f->data; }
static uint8_t **mp_aframe_get_data_rw(struct mp_aframe *f) { return f->data; }
static struct mp_frame mp_pin_out_read(void *pin) { return (struct mp_frame){0}; }
static void mp_frame_unref(struct mp_frame *f) {}
static void ao_request_reload(struct ao *ao) { ao->reloads++; }
static void ao_post_process_data(struct ao *ao, void **data, int size) {}
static void af_fill_silence(void *data, size_t size, int format) { memset(data, 0, size); }
static void mp_aframe_skip_samples(struct mp_aframe *f, int samples) {
    for (int i = 0; i < f->planes; i++)
        f->data[i] += samples * f->stride;
    f->samples -= samples;
}

__FUNCTION__

static void check_case(int kind) {
    float input_samples[4][640];
    float output[4][640];
    uint8_t *source[4];
    void *dest[4];
    for (int n = 0; n < 4; n++) {
        for (int s = 0; s < 640; s++) {
            input_samples[n][s] = (float)(n * 1000 + s + 1);
            output[n][s] = -1;
        }
        source[n] = (uint8_t *)input_samples[n];
        dest[n] = output[n];
    }
    void *pin = NULL;
    struct input in = {.pins = &pin};
    struct mp_aframe frame = {640, 4, sizeof(float), 1, source};
    struct buffer_state state = {.playing = true, .pending = &frame, .input = &in};
    struct ao ao = {4, sizeof(float), 1, 0, &state};
    bool invalid = kind >= 2 && kind <= 6;
    if (kind == 1) {
        ao.num_planes = frame.planes = 2;
    } else if (kind == 2) {
        frame.samples = 33;
        source[2] = NULL;
    } else if (kind == 3) {
        frame.planes = 2;
    } else if (kind == 4) {
        frame.stride = 2;
    } else if (kind == 5) {
        frame.format = 2;
    } else if (kind == 6) {
        frame.data = NULL;
    } else if (kind == 7) {
        state.paused = true;
    } else if (kind == 8) {
        frame.samples = 33;
    } else if (kind == 9) {
        ao.num_planes = frame.planes = 1;
        ao.sstride = frame.stride = 8;
        frame.samples = 320;
    }
    bool eof;
    int requested = kind == 2 || kind == 8 ? 640 : 33;
    int copied = read_buffer(&ao, dest, requested, &eof, true);
    if (invalid || kind == 7) {
        assert(copied == 0);
        assert(ao.reloads == invalid);
        if (invalid)
            assert(state.pending == NULL);
        for (int n = 0; n < ao.num_planes; n++)
            for (int s = 0; s < requested; s++)
                assert(output[n][s] == 0);
    } else {
        assert(copied == 33);
        assert(ao.reloads == 0);
        int values = copied * ao.sstride / sizeof(float);
        for (int n = 0; n < ao.num_planes; n++) {
            assert(memcmp(output[n], input_samples[n], values * sizeof(float)) == 0);
            if (kind == 8)
                for (int s = copied; s < requested; s++)
                    assert(output[n][s] == 0);
        }
    }
}

int main(void) {
    for (int i = 0; i < 10; i++)
        check_case(i);
    puts("10 native audio-buffer regression cases passed");
    return 0;
}
"""

with tempfile.TemporaryDirectory(prefix="audio-buffers-") as directory:
    root = Path(directory)
    test_source = root / "audio_buffers.c"
    executable = root / "audio_buffers"
    test_source.write_text(harness.replace("__FUNCTION__", function))
    subprocess.run([
        "xcrun", "clang", "-std=c11", "-O1", "-g",
        "-fsanitize=address,undefined", str(test_source), "-o", str(executable),
    ], check=True)
    result = subprocess.run(
        [str(executable)],
        env={**os.environ, "ASAN_OPTIONS": "detect_leaks=0"},
    )
    sys.exit(result.returncode)
