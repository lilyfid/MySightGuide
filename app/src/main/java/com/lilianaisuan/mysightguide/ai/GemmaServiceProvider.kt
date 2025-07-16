package com.lilianaisuan.mysightguide.ai

import android.graphics.Bitmap
import com.google.android.ai.edge.gemma.GemmaInference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaServiceProvider @Inject constructor(
    private val gemmaInference: GemmaInference?
) {
    // The new method now accepts both a text prompt and an image (Bitmap)
    suspend fun generateSceneDescription(prompt: String, image: Bitmap): String {
        if (gemmaInference == null) {
            return "The advanced AI model is not available on this device."
        }

        return try {
            // This now sends both the image and the text prompt to the model
            val response = gemmaInference.infer(prompt, image)
            response.text() ?: "I'm sorry, I couldn't describe the scene."
        } catch (e: Exception) {
            e.printStackTrace()
            "I'm sorry, an error occurred while analyzing the scene."
        }
    }
}