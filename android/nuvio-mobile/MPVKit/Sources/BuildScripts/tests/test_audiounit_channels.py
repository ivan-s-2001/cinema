from pathlib import Path
import subprocess
import sys
import tempfile


source = Path(sys.argv[1]).read_text()
mpv = Path(sys.argv[2])
start = source.index("static struct mp_chmap select_channel_map(")
end = source.index("static bool init_audiounit(", start)
selection = source[start:end]
mapping = (mpv / "audio/out/ao_coreaudio_chmap.c").read_text()
start = mapping.index("static const int speaker_map[][2]")
end = mapping.index("static const AudioChannelLayoutTag", start)
speaker_map = mapping[start:end]
start = mapping.index("int ca_label_to_mp_speaker_id(")
end = mapping.index("AudioChannelLabel mp_speaker_id_to_ca_label(", start)
conversion = mapping[start:end]
mapping = (mpv / "audio/chmap.c").read_text()
start = mapping.index("bool mp_chmap_is_valid(")
end = mapping.index("bool mp_chmap_is_empty(", start)
validation = mapping[start:end]

harness = r"""
#include <assert.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <AudioToolbox/AudioToolbox.h>
#include "audio/chmap.h"

__FUNCTIONS__

static int cases;

static void check_layout(const AudioChannelLabel *labels, UInt32 count,
                         bool shared_audio, const struct mp_chmap *expected)
{
    size_t size = offsetof(AudioChannelLayout, mChannelDescriptions) +
                  count * sizeof(AudioChannelDescription);
    AudioChannelLayout *layout = calloc(1, size);
    layout->mNumberChannelDescriptions = count;
    for (UInt32 n = 0; n < count; n++)
        layout->mChannelDescriptions[n].mChannelLabel = labels[n];
    struct mp_chmap result = select_channel_map(layout, size, shared_audio);
    assert(result.num == expected->num);
    assert(memcmp(result.speaker, expected->speaker, result.num) == 0);
    assert(mp_chmap_is_valid(&result));
    free(layout);
    cases++;
}

static void check_invalid_size(UInt32 count, UInt32 size)
{
    AudioChannelLayout layout = { .mNumberChannelDescriptions = count };
    struct mp_chmap result = select_channel_map(&layout, size, false);
    assert(result.num == 2);
    assert(result.speaker[0] == MP_SPEAKER_ID_FL);
    assert(result.speaker[1] == MP_SPEAKER_ID_FR);
    cases++;
}

int main(void)
{
    const struct mp_chmap stereo = MP_CHMAP_INIT_STEREO;
    const struct mp_chmap mono = MP_CHMAP_INIT_MONO;
    const struct mp_chmap quad = MP_CHMAP4(FL, FR, BL, BR);
    const struct mp_chmap surround = MP_CHMAP6(FL, FR, FC, LFE, BL, BR);
    const AudioChannelLabel stereo_labels[] = {
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
    };
    const AudioChannelLabel mono_labels[] = {kAudioChannelLabel_Center};
    const AudioChannelLabel quad_labels[] = {
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
        kAudioChannelLabel_LeftSurround, kAudioChannelLabel_RightSurround,
    };
    const AudioChannelLabel surround_labels[] = {
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
        kAudioChannelLabel_Center, kAudioChannelLabel_LFEScreen,
        kAudioChannelLabel_LeftSurround, kAudioChannelLabel_RightSurround,
    };
    const AudioChannelLabel duplicate_labels[] = {
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
    };
    const AudioChannelLabel unknown_labels[] = {
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
        kAudioChannelLabel_Unknown, kAudioChannelLabel_Unknown,
    };
    const AudioChannelLabel discrete_labels[] = {
        kAudioChannelLabel_Left, kAudioChannelLabel_Right,
        kAudioChannelLabel_Discrete_0, kAudioChannelLabel_Discrete_1,
    };
    check_layout(stereo_labels, 2, false, &stereo);
    check_layout(stereo_labels, 2, true, &stereo);
    check_layout(mono_labels, 1, false, &mono);
    check_layout(quad_labels, 4, false, &quad);
    check_layout(quad_labels, 4, true, &stereo);
    check_layout(surround_labels, 6, false, &surround);
    check_layout(surround_labels, 6, true, &stereo);
    check_layout(duplicate_labels, 4, false, &stereo);
    check_layout(duplicate_labels, 4, true, &stereo);
    check_layout(unknown_labels, 4, false, &stereo);
    check_layout(discrete_labels, 4, false, &stereo);
    check_invalid_size(0, sizeof(AudioChannelLayout));
    check_invalid_size(4, offsetof(AudioChannelLayout, mChannelDescriptions) - 1);
    check_invalid_size(4, sizeof(AudioChannelLayout));
    check_invalid_size(MP_NUM_CHANNELS + 1, sizeof(AudioChannelLayout));
    struct mp_chmap result = select_channel_map(NULL, 0, false);
    assert(result.num == 2);
    cases++;
    printf("%d AudioUnit channel-layout regression cases passed\n", cases);
    return 0;
}
"""

with tempfile.TemporaryDirectory(prefix="audiounit-channels-") as directory:
    root = Path(directory)
    test_source = root / "audiounit_channels.c"
    executable = root / "audiounit_channels"
    functions = speaker_map + conversion + validation + selection
    test_source.write_text(harness.replace("__FUNCTIONS__", functions))
    subprocess.run([
        "xcrun", "clang", "-std=c11", "-O1", "-g",
        "-fsanitize=address,undefined", "-I", str(mpv),
        str(test_source), "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True)
