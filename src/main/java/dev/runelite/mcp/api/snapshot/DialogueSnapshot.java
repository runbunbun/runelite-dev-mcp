package dev.runelite.mcp.api.snapshot;

import java.util.List;

public final class DialogueSnapshot {
    public final boolean open;
    public final boolean canContinue;
    public final boolean hasOptions;
    public final List<String> options;
    public final String text;
    public final String speakerName;

    public DialogueSnapshot(boolean open, boolean canContinue, boolean hasOptions,
                            List<String> options, String text, String speakerName) {
        this.open = open; this.canContinue = canContinue; this.hasOptions = hasOptions;
        this.options = options; this.text = text; this.speakerName = speakerName;
    }

    public static DialogueSnapshot closed() {
        return new DialogueSnapshot(false, false, false, List.of(), null, null);
    }
}
