package com.rd.englishcoach;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/**
 * ML Kit 端上 OCR：中英文混合识别（bundled 版，离线免费）。
 *
 * <p>⚠️ 必须用 {@code ChineseTextRecognizerOptions}（包含汉字 + 拉丁模型），
 * 不能用 {@code LatinTextRecognizerOptions}（只有英文）。</p>
 */
public final class OcrEngine {

    private static final String TAG = "OcrEngine";

    private OcrEngine() {}

    interface Callback {
        void onResult(String text);
        void onError(String error);
    }

    /** 识别 Bitmap 中的文字，回调在主线程。 */
    public static void recognize(Bitmap bitmap, Callback callback) {
        if (bitmap == null) {
            callback.onError("bitmap is null");
            return;
        }
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognizer recognizer =
                    TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        String result = cleanUp(text);
                        Log.i(TAG, "OCR result: " + result.length() + " chars");
                        callback.onResult(result);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "OCR failed", e);
                        callback.onError("OCR failed: " + e.getMessage());
                    });
        } catch (Exception e) {
            Log.e(TAG, "OCR init failed", e);
            callback.onError("OCR init failed: " + e.getMessage());
        }
    }

    /** 整理识别结果：合并换行、去除多余空白。 */
    static String cleanUp(Text text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText().trim();
                if (!lineText.isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(lineText);
                }
            }
        }
        return sb.toString().trim();
    }
}
