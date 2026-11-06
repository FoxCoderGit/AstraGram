package org.telegram.ui.Components;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;
import org.telegram.messenger.GhostPlayback;
import java.nio.ByteBuffer;

/** PCM gain with a smooth limiter; always in the chain so toggling is immediate. */
public final class GhostBoostAudioProcessor extends BaseAudioProcessor {
    @Override public AudioFormat onConfigure(AudioFormat input) {
        return input.encoding == C.ENCODING_PCM_16BIT ? input : AudioFormat.NOT_SET;
    }
    @Override public void queueInput(ByteBuffer input) {
        // Media3 queues EMPTY_BUFFER while draining the pipeline. BaseAudioProcessor
        // also starts with EMPTY_BUFFER, so copying it would be a self-copy.
        if (!input.hasRemaining()) return;
        ByteBuffer output = replaceOutputBuffer(input.remaining());
        boolean enabled = GhostPlayback.boost;
        if (!enabled) output.put(input);
        else while (input.remaining() >= 2) output.putShort(GhostPlayback.amplify(input.getShort()));
        output.flip();
    }
}
