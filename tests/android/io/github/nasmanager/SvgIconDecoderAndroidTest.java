package io.github.nasmanager;

import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;

/** Device-side smoke fixtures; run with an Android runtime, not the host JVM stubs. */
public final class SvgIconDecoderAndroidTest {
    public static void main(String[] args) {
        assertVisible("<svg viewBox='0 0 32 32'><defs><linearGradient id='g'>"
                + "<stop style='stop-color:#AA5CC3'/></linearGradient></defs>"
                + "<path fill='url(#g)' d='M2 2 L30 2 L16 30 Z'/></svg>");
        assertVisible("<svg viewBox='0 0 32 32'><style>.logo{fill:#00a4dc}</style>"
                + "<path class='logo' transform='translate(2 2)' d='M0 0h28v28H0z'/></svg>");
        if (SvgIconDecoder.decode(bytes("<!DOCTYPE svg [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<svg viewBox='0 0 1 1'><path d='M0 0h1v1z'/></svg>")) != null) {
            throw new AssertionError("Unsafe DOCTYPE fixture was accepted");
        }
        System.out.println("SvgIconDecoder Android fixtures passed");
    }

    private static void assertVisible(String svg) {
        Bitmap bitmap = SvgIconDecoder.decode(bytes(svg));
        if (bitmap == null) throw new AssertionError("SVG fixture did not decode");
        boolean visible = false;
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int color : pixels) if ((color >>> 24) != 0) { visible = true; break; }
        if (!visible) throw new AssertionError("SVG fixture rendered fully transparent");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
