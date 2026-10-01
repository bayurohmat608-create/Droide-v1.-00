package com.baystudio.droide.core;


public enum CodingKeyboardMode {
    COMPACT("compact", true, false),
    NO_CORRECTIONS("no_corrections", false, false),
    NORMAL("normal", false, true);

    
    public static final CodingKeyboardMode DEFAULT = COMPACT;

    private final String storageValue;
    private final boolean rawInput;
    private final boolean wordCorrections;

    CodingKeyboardMode(String storageValue, boolean rawInput, boolean wordCorrections) {
        this.storageValue = storageValue;
        this.rawInput = rawInput;
        this.wordCorrections = wordCorrections;
    }

    public String getStorageValue() { return storageValue; }
    public boolean requestsRawInput() { return rawInput; }
    public boolean allowsWordCorrections() { return wordCorrections; }

    public static CodingKeyboardMode fromStoredValue(String value) {
        for (CodingKeyboardMode mode : values()) {
            if (mode.storageValue.equals(value)) return mode;
        }
        return DEFAULT;
    }
}
