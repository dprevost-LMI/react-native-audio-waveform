package com.audiowaveform

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates

class AudioWaveformExtractorRateLimiter(private var nbOfParallelAllowedExtraction: Int = 3) {

    private val nbOfAllowedExtraction = AtomicInteger(nbOfParallelAllowedExtraction)
    private val extractionQueue: ConcurrentLinkedQueue<WaveformExtractor> = ConcurrentLinkedQueue()

    fun add(waveformExtractor: WaveformExtractor, previousExtractor: WaveformExtractor?) {
        extractionQueue.add(waveformExtractor)
        previousExtractor?.let { extractionQueue.remove(it) }
        processQueue()
    }

    fun continueQueue() {
        nbOfAllowedExtraction.incrementAndGet()
        processQueue()
    }

    @Synchronized
    private fun getNext(): WaveformExtractor? {
        return if (extractionQueue.isEmpty() || nbOfAllowedExtraction.get() <= 0) null
        else extractionQueue.poll()?.also { nbOfAllowedExtraction.decrementAndGet() }
    }

    private fun processQueue() {
        getNext()?.startDecode()
    }

    fun reset() {
        extractionQueue.forEach { it.stop() }
        extractionQueue.clear()
        nbOfAllowedExtraction.set(nbOfParallelAllowedExtraction)
    }
}