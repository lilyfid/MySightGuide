package com.lilianaisuan.mysightguide.di

import android.content.Context
import com.google.android.ai.edge.gemma.Gemma
import com.google.android.ai.edge.gemma.GemmaInference
import com.google.android.gms.tasks.Tasks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideGemmaInference(@ApplicationContext context: Context): GemmaInference? {
        return try {
            // Initialize the Gemma singleton. This will download the model on first run.
            // Tasks.await() blocks until initialization is complete.
            // This is safe here because Hilt runs it on a background thread.
            val gemma = Tasks.await(Gemma.initialize(context))

            // Create an inference instance with the desired model.
            gemma.createGemmaInference(
                // This is the efficient, CPU-friendly Gemma 3N model.
                "gemma-3n-e2b-it",
                GemmaInference.SessionOptions.builder().setNumDecodeSteps(256).build()
            )
        } catch (e: Exception) {
            // This will fail on unsupported devices. Returning null is our safety mechanism.
            e.printStackTrace()
            null
        }
    }
}