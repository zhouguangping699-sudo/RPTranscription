package com.rp.rptranscription.hymt;

public final class HyMtNative {
    static {
        System.loadLibrary("hy_mt_jni");
    }

    private HyMtNative() {
    }

    public static native long nativeCreate(String modelPath, int nThreads, int nCtx);

    public static native String nativeTranslate(long handle, String prompt);

    public static native void nativeRelease(long handle);
}
